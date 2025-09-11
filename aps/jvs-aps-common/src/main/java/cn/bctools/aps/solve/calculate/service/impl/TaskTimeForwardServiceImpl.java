package cn.bctools.aps.solve.calculate.service.impl;

import cn.bctools.aps.annotation.PlanningDirection;
import cn.bctools.aps.entity.dto.ProcessRouteNodePropertiesDTO;
import cn.bctools.aps.entity.dto.ProcessUseMainResourcesDTO;
import cn.bctools.aps.entity.enums.PlanningDirectionEnum;
import cn.bctools.aps.entity.enums.ProcessRelationshipEnum;
import cn.bctools.aps.solve.calculate.service.TaskTimeService;
import cn.bctools.aps.solve.calculate.service.dto.PendingChangeTaskDTO;
import cn.bctools.aps.solve.model.MainProductionResource;
import cn.bctools.aps.solve.model.ProductionTask;
import cn.bctools.aps.solve.model.SchedulingSolution;
import cn.bctools.aps.solve.model.WorkCalendar;
import cn.bctools.aps.solve.util.DurationUtils;
import cn.bctools.aps.solve.util.TaskCalendarUtils;
import cn.bctools.common.exception.BusinessException;
import cn.bctools.common.utils.ObjectNull;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 3. 分配机制原理
 * 3.1 OptaPlanner的规划过程
 * 初始化阶段：创建初始解决方案，包含所有任务和资源
 * 构造阶段：使用构造启发式算法将任务分配到资源
 * 局部搜索阶段：通过移动操作优化解决方案
 * 示例场景
 * 假设有以下任务和资源：
 * 任务信息：
 * T1: 工序需要资源R1，持续时间1小时
 * T2: 工序需要资源R1，持续时间1.5小时
 * T3: 工序需要资源R1，持续时间2小时
 * 资源信息：
 * R1: 主资源，工作时间为09:00-17:00
 * 初始状态：
 * 计划开始时间: 2025-09-09 09:00:00
 * R1.taskList: []
 * 第一次调用：
 * 调用: updateTaskStartTime(scoreDirector, R1, 0)
 * 参数:
 * - scoreDirector: OptaPlanner框架提供的ScoreDirector
 * - resource: R1资源对象
 * - fromIndex: 0 (从第一个位置开始)
 *
 * 处理过程:
 * 1. 获取R1.taskList = [T1]
 * 2. 从索引0开始处理
 * 3. T1.startTime = 2025-09-09 09:00:00 (计划开始时间)
 * 4. T1.endTime = 2025-09-09 10:00:00 (开始时间 + 1小时)
 * 第二次调用：
 * 调用: updateTaskStartTime(scoreDirector, R1, 1)
 * 参数:
 * - scoreDirector: OptaPlanner框架提供的ScoreDirector
 * - resource: R1资源对象
 * - fromIndex: 1 (从第二个位置开始)
 *
 * 处理过程:
 * 1. 获取R1.taskList = [T1, T2]
 * 2. 从索引1开始处理(T2)
 * 3. T2.startTime = 2025-09-09 10:00:00 (T1的结束时间)
 * 4. T2.endTime = 2025-09-09 11:30:00 (开始时间 + 1.5小时)
 * 第三次调用（移动操作）： 假设T2从R1移动到R2：
 * 调用1: updateTaskStartTime(scoreDirector, R1, 1)
 * 参数:
 * - resource: R1资源对象
 * - fromIndex: 1
 *
 * 处理过程:
 * 1. 获取R1.taskList = [T1, T3] (T2已移除)
 * 2. 从索引1开始处理(T3)
 * 3. T3.startTime = 2025-09-09 10:00:00 (T1的结束时间)
 * 4. T3.endTime = 2025-09-09 12:00:00 (开始时间 + 2小时)
 *
 * 调用2: updateTaskStartTime(scoreDirector, R2, 0)
 * 参数:
 * - resource: R2资源对象
 * - fromIndex: 0
 *
 * 处理过程:
 * 1. 获取R2.taskList = [T2]
 * 2. 从索引0开始处理(T2)
 * 3. T2.startTime = 2025-09-09 09:00:00 (R2的计划开始时间)
 * 4. T2.endTime = 2025-09-09 10:30:00 (开始时间 + 1.5小时)
 * 5. 核心机制总结
 * 5.1 增量更新机制
 * 通过fromIndex参数实现增量更新，避免重复计算未受影响的任务。
 * 5.2 时间传播机制
 * 当一个任务时间发生变化时，通过队列机制将影响传播给后续任务：
 * 5.3 约束满足机制
 * 通过ApsHardSoftScoreConstraintProvider定义的约束确保解决方案的有效性：
 * 资源兼容性约束
 * 时间不重叠约束
 * 任务必须分配约束
 */

/**
 * @author jvs
 * 计算任务时间 —— 正排
 * <p>
 * 从计划开始时间开始，往未来安排任务
 */
@PlanningDirection(direction = PlanningDirectionEnum.FORWARD)
@Slf4j
@Service
public class TaskTimeForwardServiceImpl implements TaskTimeService {

    // 缓存用于存储任务时间计算结果
    private final Map<String, TaskTimeCache> timeCalculationCache = new ConcurrentHashMap<>();

    // 用于跟踪传播深度，防止无限递归
    private final ThreadLocal<Integer> propagationDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    /**
     * 检查资源是否与任务兼容
     *
     * @param resource 资源
     * @param task 任务
     * @return 是否兼容
     */
    private boolean isResourceCompatibleWithTask(MainProductionResource resource, ProductionTask task) {
        // 检查任务和工序信息是否完整
        if (task.getProcess() == null || task.getProcess().getUseMainResources() == null) {
            log.warn("任务工序信息不完整: taskCode={}", task.getCode());
            return false;
        }

        // 检查资源ID是否为空
        if (resource.getId() == null) {
            log.warn("资源ID为空: resourceCode={}", resource.getCode());
            return false;
        }

        // 检查是否在允许的资源列表中
        return task.getProcess().getUseMainResources().stream()
                .filter(ObjectNull::isNotNull)
                .anyMatch(reqResource ->
                        ObjectNull.isNotNull(reqResource.getId()) &&
                                reqResource.getId().equals(resource.getId()));
    }

    /**
     * 验证任务分配的有效性
     *
     * @param task 任务
     * @param resource 资源
     * @return 验证结果
     */
    private ValidationResult validateTaskAssignment(ProductionTask task, MainProductionResource resource) {
        ValidationResult result = new ValidationResult();

        // 资源兼容性检查
        if (!isResourceCompatibleWithTask(resource, task)) {
            result.addViolation("资源不兼容", ValidationResult.Severity.ERROR);
        }

        // 时间重叠检查
        if (hasTimeOverlap(task, resource)) {
            result.addViolation("时间重叠", ValidationResult.Severity.WARNING);
        }

        return result;
    }

    /**
     * 检查任务是否与资源中的其他任务时间重叠
     */
    private boolean hasTimeOverlap(ProductionTask task, MainProductionResource resource) {
        if (task.getStartTime() == null || task.getEndTime() == null) {
            return false;
        }

        return resource.getTaskList().stream()
                .filter(t -> !t.getId().equals(task.getId()))
                .filter(t -> t.getStartTime() != null && t.getEndTime() != null)
                .anyMatch(t -> {
                    return t.getStartTime().isBefore(task.getEndTime()) &&
                            t.getEndTime().isAfter(task.getStartTime());
                });
    }

    /**
     * @param scoreDirector
     * @param resource 主资源
     * @param fromIndex 变更任务所在目标资源的下标
     */
    @Override
    public void updateTaskStartTime(ScoreDirector<SchedulingSolution> scoreDirector, MainProductionResource resource, int fromIndex) {
        log.info("开始更新资源任务时间: resource={}, resourceName={}, fromIndex={} taskListSize={}",
                resource.getCode(), resource.getName(), fromIndex, resource.getTaskList().size());

        // 清除该资源的缓存
        clearResourceCache(resource);

        // 获取计划开始时间作为基准时间
        LocalDateTime beginTime = scoreDirector.getWorkingSolution().getPlanTime();
        // 获取所有任务列表
        List<ProductionTask> allTasks = scoreDirector.getWorkingSolution().getTasks();
        // 用于记录已处理的任务ID，避免重复处理
        Set<String> historyQueueJobIds = new HashSet<>();
        // 待处理的任务队列
        Queue<PendingChangeTaskDTO> uncheckedSuccessorQueue = new ArrayDeque<>();
        // 创建待处理任务对象
        PendingChangeTaskDTO pendingChangeTask = new PendingChangeTaskDTO()
                .setResource(resource)
                .setFromIndex(fromIndex);
        // 将当前任务加入待处理队列
        uncheckedSuccessorQueue.add(pendingChangeTask);

        // 循环处理队列中的所有任务
        while (!uncheckedSuccessorQueue.isEmpty()) {
            // 取出队列中的一个待处理任务
            PendingChangeTaskDTO update = uncheckedSuccessorQueue.remove();
            log.debug("处理资源任务变更: resource={}, resourceName={}, taskListSize={}",
                    update.getResource().getCode(), update.getResource().getName(), update.getResource().getTaskList().size());

            // 获取在该资源的任务列表
            List<ProductionTask> tasks = update.getResource().getTaskList();
            // 获取起始索引
            int fromIdx = update.getFromIndex();

            // 如果任务列表为空或索引超出范围，则跳过
            if (ObjectNull.isNull(tasks) || tasks.size() - 1 < fromIdx) {
                log.debug("任务列表为空或索引超出范围，跳过处理: tasks={}, fromIdx={}", tasks, fromIdx);
                continue;
            }

            // 预先检查资源兼容性并处理不兼容任务
            handleIncompatibleTasks(scoreDirector, update.getResource(), tasks, fromIdx);

            // 获取当前变更任务[即被分派到当前资源的任务]在资源中的前一个任务
            ProductionTask sourcePreviousTask = getSourcePreviousTask(tasks, fromIdx);

            // 计算前一个任务的结束时间，如果是第一个任务则使用计划开始时间
            LocalDateTime sourceTaskPreviousEndTime = fromIdx == 0 ? beginTime :
                    Optional.ofNullable(sourcePreviousTask).map(ProductionTask::getEndTime).orElseGet(() -> beginTime);
            // 设置资源前一个任务的结束时间为基准时间
            LocalDateTime resourcePreviousEndTime = sourceTaskPreviousEndTime;

            log.debug("资源时间基准计算: resource={}, resourceName={}, fromIdx={}, sourcePreviousTask={}, sourceTaskPreviousEndTime={}",
                    update.getResource().getCode(),
                    update.getResource().getName(),
                    fromIdx,
                    sourcePreviousTask != null ? sourcePreviousTask.getCode() : "null",
                    sourceTaskPreviousEndTime);

            // 记录不需要更新的前置任务ID
            Set<String> notUpdateFrontTaskIds = new HashSet<>();
            // 记录已处理的任务，用于传播控制
            Set<String> processedTaskIds = new HashSet<>();

            // 遍历从起始索引开始的所有任务
            for (int idx = fromIdx; idx < tasks.size(); idx++) {
                ProductionTask currentTask = tasks.get(idx);
                log.debug("处理任务: taskCode={}, taskId={}, resource={}, resourceName={}, index={}",
                        currentTask.getCode(), currentTask.getId(),
                        update.getResource().getCode(), update.getResource().getName(),
                        idx);

                // 将当前任务ID加入历史记录
                historyQueueJobIds.add(currentTask.getId());

                // 如果任务已被锁定，则更新资源前一个任务的结束时间并继续
                if (currentTask.getPinned()) {
                    log.debug("任务已锁定，跳过时间计算: taskCode={}", currentTask.getCode());
                    resourcePreviousEndTime = currentTask.getEndTime();
                    continue;
                }

                // 获取当前资源中当前任务之前的所有任务
                List<ProductionTask> currentResourcePreviousTasks = tasks.subList(0, idx);
                // 记录前置任务信息
                if (currentTask.getFrontTaskCodes() != null) {
                    log.debug("任务前置任务: taskCode={}, frontTasks={}", currentTask.getCode(), currentTask.getFrontTaskCodes());
                }

                // 计算当前任务的开始时间
                LocalDateTime startTime = calculateStartTimeWithCache(
                        update.getResource(),           // 资源
                        currentTask,                    // 当前任务
                        sourcePreviousTask,            // 资源前一个任务
                        resourcePreviousEndTime,       // 资源前一个任务结束时间
                        allTasks,                      // 所有任务
                        currentResourcePreviousTasks); // 当前资源中之前的任务

                log.debug("任务时间计算结果: taskCode={}, calculatedStartTime={}", currentTask.getCode(), startTime);

                // 如果当前任务ID不在不需要更新的集合中
                if (!notUpdateFrontTaskIds.contains(currentTask.getId())) {
                    // 通知OptaPlanner开始时间将要改变
                    scoreDirector.beforeVariableChanged(currentTask, "startTime");
                    // 设置任务开始时间
                    currentTask.setStartTime(startTime);
                    // 通知OptaPlanner开始时间已改变
                    scoreDirector.afterVariableChanged(currentTask, "startTime");

                    // 记录时间变更
                    if (startTime != null) {
                        log.info("更新任务时间: taskCode={}, startTime={}, endTime={}",
                                currentTask.getCode(), startTime, currentTask.getEndTime());
                    } else {
                        log.info("任务时间置空: taskCode={}", currentTask.getCode());
                    }

                    // 添加变更任务的前置任务ID到不需要更新集合
                    addChangedTaskIds(currentTask, notUpdateFrontTaskIds);

                    // 如果开始时间不为空，则更新资源前一个任务结束时间为当前任务结束时间
                    if (ObjectNull.isNotNull(startTime)) {
                        resourcePreviousEndTime = currentTask.getEndTime();
                        log.debug("更新资源时间基准: resource={}, newBaseTime={}",
                                update.getResource().getCode(), resourcePreviousEndTime);
                    }

                    // 缓存计算结果
                    cacheTaskTime(currentTask, startTime);

                    // 记录已处理任务
                    processedTaskIds.add(currentTask.getId());

                    // 查找当前任务的后续任务并加入处理队列
                    findAndProcessSuccessorTasks(scoreDirector, currentTask, historyQueueJobIds,
                            uncheckedSuccessorQueue, processedTaskIds);
                }
            }
        }

        log.info("资源任务时间更新完成: resource={}", resource.getCode());
    }

    /**
     * 处理不兼容任务
     */
    private void handleIncompatibleTasks(ScoreDirector<SchedulingSolution> scoreDirector,
                                         MainProductionResource resource,
                                         List<ProductionTask> tasks,
                                         int fromIndex) {
        List<ProductionTask> incompatibleTasks = tasks.stream()
                .skip(fromIndex)
                .filter(task -> !isResourceCompatibleWithTask(resource, task))
                .collect(Collectors.toList());

        // 如果不兼容任务过多，记录警告
        if (incompatibleTasks.size() > tasks.size() * 0.3) {
            log.warn("资源与过多任务不兼容，可能需要重新规划: resource={}, incompatibleCount={}",
                    resource.getCode(), incompatibleTasks.size());
        }

        // 处理不兼容任务
        incompatibleTasks.forEach(task -> {
            log.warn("检测到资源与任务不兼容，将任务时间置空: taskCode={}, resource={}",
                    task.getCode(), resource.getCode());
            // 通知OptaPlanner开始时间将要改变
            scoreDirector.beforeVariableChanged(task, "startTime");
            // 设置任务开始时间为null
            task.setStartTime(null);
            // 通知OptaPlanner开始时间已改变
            scoreDirector.afterVariableChanged(task, "startTime");
        });
    }

    /**
     * 查找并处理后续任务
     */
    private void findAndProcessSuccessorTasks(ScoreDirector<SchedulingSolution> scoreDirector,
                                              ProductionTask currentTask,
                                              Set<String> historyQueueJobIds,
                                              Queue<PendingChangeTaskDTO> uncheckedSuccessorQueue,
                                              Set<String> processedTaskIds) {
        scoreDirector.getWorkingSolution().getTasks()
                .stream()
                .filter(j -> ObjectNull.isNull(j.getMergeTaskCode())) // 过滤非合并任务
                .filter(j -> j.getFrontTaskCodes() != null && j.getFrontTaskCodes().contains(currentTask.getCode())) // 过滤后续任务
                .filter(j -> j.getResource() != null) // 过滤已分配资源的任务
                .forEach(nextJob -> {
                    log.debug("发现后续任务: currentTask={}, nextTask={}", currentTask.getCode(), nextJob.getCode());

                    // 如果后续任务未被处理过
                    if (!historyQueueJobIds.contains(nextJob.getId()) && !processedTaskIds.contains(nextJob.getId())) {
                        // 获取后续任务所在资源的任务列表
                        List<ProductionTask> nextResourceTasks = nextJob.getResource().getTaskList();
                        // 查找后续任务在资源中的索引
                        int optionalIndex = IntStream.range(0, nextResourceTasks.size())
                                .filter(i -> nextResourceTasks.get(i).getId().equals(nextJob.getId()))
                                .findFirst().orElse(0);
                        // 创建后续任务的待处理对象
                        PendingChangeTaskDTO updateNextJob = new PendingChangeTaskDTO()
                                .setResource(nextJob.getResource())
                                .setFromIndex(optionalIndex);
                        // 将当前任务ID加入历史记录
                        historyQueueJobIds.add(currentTask.getId());
                        // 将后续任务加入待处理队列
                        uncheckedSuccessorQueue.add(updateNextJob);

                        log.debug("添加后续任务到处理队列: nextTask={}, resource={}, index={}",
                                nextJob.getCode(), nextJob.getResource().getCode(), optionalIndex);
                    } else {
                        log.debug("后续任务已处理过，跳过: nextTask={}", nextJob.getCode());
                    }
                });
    }

    /**
     * 找到指定下标前面第一个有结束时间的任务
     *
     * @param tasks     任务集合
     * @param fromIndex 下标
     * @return 下标前第一个有结束时间的任务
     */
    private ProductionTask getSourcePreviousTask(List<ProductionTask> tasks, int fromIndex) {
        // 简化日志记录
        if (fromIndex == 0) {
            return null;
        }
        ProductionTask previousTask = null;
        for (int i = fromIndex - 1; i >= 0; i--) {
            if (ObjectNull.isNotNull(previousTask)) {
                break;
            }
            ProductionTask task = tasks.get(i);
            if (ObjectNull.isNull(task.getEndTime())) {
                continue;
            }
            previousTask = task;
        }

        return previousTask;
    }

    /**
     * 记录已变更的任务id
     *
     * @param job         任务
     * @param frontJobIds 前置任务id
     */
    private void addChangedTaskIds(ProductionTask job, Set<String> frontJobIds) {
        if (job.getFrontTasks() == null) {
            return;
        }
        ArrayDeque<ProductionTask> frontJobStack = new ArrayDeque<>();
        job.getFrontTasks().forEach(frontJobStack::push);
        while (!frontJobStack.isEmpty()) {
            ProductionTask frontJob = frontJobStack.pop();
            frontJobIds.add(frontJob.getId());
            if (frontJob.getFrontTasks() != null) {
                frontJob.getFrontTasks().forEach(frontJobStack::push);
            }
        }
    }

    /**
     * 计算任务开始时间（带缓存）
     */
    private LocalDateTime calculateStartTimeWithCache(MainProductionResource mainProductionResource,
                                                      ProductionTask currentTask,
                                                      ProductionTask resourcePreviousTask,
                                                      LocalDateTime resourcePreviousEndTime,
                                                      List<ProductionTask> allTasks,
                                                      List<ProductionTask> currentResourcePreviousTasks) {
        // 生成缓存键
        String cacheKey = generateCacheKey(mainProductionResource, currentTask, resourcePreviousEndTime);

        // 检查缓存
        TaskTimeCache cachedResult = timeCalculationCache.get(cacheKey);
        if (cachedResult != null && !isCacheExpired(cachedResult)) {
            log.debug("使用缓存结果: taskCode={}", currentTask.getCode());
            return cachedResult.getStartTime();
        }

        // 计算任务开始时间
        LocalDateTime startTime = calculateStartTime(
                mainProductionResource,
                currentTask,
                resourcePreviousTask,
                resourcePreviousEndTime,
                allTasks,
                currentResourcePreviousTasks);

        // 缓存结果
        if (startTime != null) {
            timeCalculationCache.put(cacheKey, new TaskTimeCache(startTime, System.currentTimeMillis()));
        }

        return startTime;
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(MainProductionResource resource, ProductionTask task, LocalDateTime baseTime) {
        return resource.getId() + "_" + task.getId() + "_" +
                (baseTime != null ? baseTime.toString() : "null");
    }

    /**
     * 检查缓存是否过期（5分钟）
     */
    private boolean isCacheExpired(TaskTimeCache cache) {
        return System.currentTimeMillis() - cache.getTimestamp() > 5 * 60 * 1000;
    }

    /**
     * 清除资源相关缓存
     */
    private void clearResourceCache(MainProductionResource resource) {
        timeCalculationCache.entrySet().removeIf(entry ->
                entry.getKey().startsWith(resource.getId() + "_"));
    }

    /**
     * 缓存任务时间
     */
    private void cacheTaskTime(ProductionTask task, LocalDateTime startTime) {
        // 这里可以实现更复杂的缓存策略
    }

    /**
     * 计算任务开始时间
     *
     * @param currentTask             任务
     * @param resourcePreviousTask    当前任务所在资源的前一个任务
     * @param resourcePreviousEndTime 当前任务所在资源的前一个任务的结束时间
     * @param allTasks                所有排程任务
     * @return 任务开始时间
     */
    private LocalDateTime calculateStartTime(MainProductionResource mainProductionResource,
                                             ProductionTask currentTask,
                                             ProductionTask resourcePreviousTask,
                                             LocalDateTime resourcePreviousEndTime,
                                             List<ProductionTask> allTasks,
                                             List<ProductionTask> currentResourcePreviousTasks) {
        // 检查资源是否能处理该任务
        if (!isResourceCompatibleWithTask(mainProductionResource, currentTask)) {
            log.warn("资源无法处理任务: taskCode={}, resourceId={}, resource={}, resourceName={}",
                    currentTask.getCode(),
                    mainProductionResource.getId(), mainProductionResource.getCode(), mainProductionResource.getName());
            return null;
        }
        log.debug("开始计算任务时间: taskCode={}, resource={}, resourceName={}",
                currentTask.getCode(),
                mainProductionResource.getCode(),
                mainProductionResource.getName());

        // 检查资源是否能处理该任务
        List<String> requiredResourceIds = currentTask.getProcess().getUseMainResources()
                .stream()
                .map(ProcessUseMainResourcesDTO::getId)
                .collect(Collectors.toList());

        String currentResourceId = mainProductionResource.getId();
        boolean canHandleTask = requiredResourceIds.contains(currentResourceId);

        log.debug("资源匹配检查: taskCode={}, requiredResources={}, currentResource={}, canHandle={}",
                currentTask.getCode(), requiredResourceIds, currentResourceId, canHandleTask);

        if (!canHandleTask) {
            log.warn("资源无法处理任务: taskCode={}, requiredResources={}, resourceId={}, resource={}, resourceName={}",
                    currentTask.getCode(), requiredResourceIds,
                    mainProductionResource.getId(), mainProductionResource.getCode(), mainProductionResource.getName());
            return null;
        }

        // 计算任务开始时间
        LocalDateTime startTime = calculateStartTime(
                currentTask,
                resourcePreviousTask,
                resourcePreviousEndTime,
                allTasks,
                currentResourcePreviousTasks);

        if (ObjectNull.isNull(startTime)) {
            log.debug("计算得到空开始时间: taskCode={}", currentTask.getCode());
            return startTime;
        }

        List<ProductionTask> resourcePinnedTasks = mainProductionResource.getTaskList().stream()
                .filter(ProductionTask::getPinned)
                .toList();
        if (ObjectNull.isNull(resourcePinnedTasks)) {
            log.debug("资源无锁定任务，直接返回计算时间: taskCode={}, startTime={}", currentTask.getCode(), startTime);
            return startTime;
        }

        currentTask.setStartTime(startTime);
        log.debug("设置临时开始时间以检查重叠: taskCode={}, tempStartTime={}", currentTask.getCode(), startTime);

        Optional<LocalDateTime> maxOverlappingTime = resourcePinnedTasks.stream()
                .filter(t -> t.getEndTime().isAfter(currentTask.getStartTime()) && t.getStartTime().isBefore(currentTask.getEndTime()))
                .map(ProductionTask::getEndTime)
                .max(Comparator.naturalOrder());
        if (maxOverlappingTime.isPresent()) {
            log.debug("发现时间重叠，重新计算时间: taskCode={}, overlappingEndTime={}", currentTask.getCode(), maxOverlappingTime.get());
            return calculateStartTime(currentTask, resourcePreviousTask, maxOverlappingTime.get(), allTasks, currentResourcePreviousTasks);
        }

        log.debug("任务时间计算完成: taskCode={}, finalStartTime={}", currentTask.getCode(), startTime);
        return startTime;
    }

    @Override
    public LocalDateTime calculateStartTime(ProductionTask task, ProductionTask resourcePreviousTask, LocalDateTime resourcePreviousEndTime, List<ProductionTask> tasks, List<WorkCalendar> workCalendars, List<ProductionTask> currentResourcePreviousTasks) {
        LocalDateTime earliestStartTime = calculateStartTime(task, resourcePreviousTask, resourcePreviousEndTime, tasks, currentResourcePreviousTasks);
        log.debug("应用工作日历前的时间: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
        LocalDateTime finalStartTime = TaskCalendarUtils.calculateStartTime(earliestStartTime, workCalendars, PlanningDirectionEnum.FORWARD);
        log.debug("应用工作日历后的时间: taskCode={}, finalStartTime={}", task.getCode(), finalStartTime);
        return finalStartTime;
    }

    /**
     * 计算任务开始时间
     *
     * @param currentTask             任务
     * @param resourcePreviousTask    当前任务所在资源的前一个任务
     * @param resourcePreviousEndTime 当前任务所在资源的前一个任务的结束时间
     * @param tasks                   任务集合
     * @return 任务开始时间
     */
    private LocalDateTime calculateStartTime(ProductionTask currentTask,
                                             ProductionTask resourcePreviousTask,
                                             LocalDateTime resourcePreviousEndTime,
                                             List<ProductionTask> tasks,
                                             List<ProductionTask> currentResourcePreviousTasks) {
        log.debug("根据工序关系计算任务开始时间: taskCode={}, processRelationship={}",
                currentTask.getCode(), currentTask.getProcess().getProcessRelationship());

        // ES关系
        if (currentTask.getStartTask() || ProcessRelationshipEnum.ES.equals(currentTask.getProcess().getProcessRelationship())) {
            log.debug("使用ES关系计算时间: taskCode={}", currentTask.getCode());
            return calculateStartTimeEs(currentTask, resourcePreviousTask, resourcePreviousEndTime, tasks, currentResourcePreviousTasks);
        }

        // EE关系
        if (ProcessRelationshipEnum.EE.equals(currentTask.getProcess().getProcessRelationship())) {
            log.debug("使用EE关系计算时间: taskCode={}", currentTask.getCode());
            return calculateStartTimeEe(currentTask, resourcePreviousTask, resourcePreviousEndTime, tasks, currentResourcePreviousTasks);
        }
        throw new BusinessException("未设置工序关系");
    }

    /**
     * ES关系预计算任务开始时间: 前工序任务全部完成，才能开始当前工序任务
     *
     * @param task                 当前任务（待计算开始时间的任务）
     * @param resourcePreviousTask 当前任务所在资源的前一个任务
     * @param previousEndTime      当前任务所在资源的前一个任务的结束时间
     * @param tasks 所有排程任务
     * @return 当前任务开始时间
     */
    private LocalDateTime calculateStartTimeEs(ProductionTask task,
                                               ProductionTask resourcePreviousTask,
                                               LocalDateTime previousEndTime,
                                               List<ProductionTask> tasks,
                                               List<ProductionTask> currentResourcePreviousTasks) {
        log.debug("ES关系时间计算开始: taskCode={}", task.getCode());

        // 获取执行当前任务之前的结束时间最晚的任务
        PreTask preTask = getPreTask(task, resourcePreviousTask, previousEndTime, tasks);
        log.debug("ES关系前置任务分析: taskCode={}, earliestStartTime={}, earliestStartTimeInProcessTask={}",
                task.getCode(), preTask.getEarliestStartTime(),
                preTask.getEarliestStartTimeInProcessTask() != null ?
                        preTask.getEarliestStartTimeInProcessTask().getCode() : "null");

        // 计算开始时间
        LocalDateTime result = calculateEarliestStartTimeWithPreInterval(task, currentResourcePreviousTasks, preTask.getEarliestStartTime());
        log.debug("ES关系时间计算完成: taskCode={}, result={}", task.getCode(), result);
        return result;
    }

    /**
     * EE关系预计算任务开始时间: 当前任务不需要等前工序任务完成，就可以开始
     *
     * @param task                 当前任务（待计算开始时间的任务）
     * @param resourcePreviousTask 当前任务所在资源的前一个任务
     * @param previousEndTime      当前任务所在资源的前一个任务的结束时间
     * @param tasks 所有排程任务
     * @return 当前任务开始时间
     */
    private LocalDateTime calculateStartTimeEe(ProductionTask task, ProductionTask resourcePreviousTask, LocalDateTime previousEndTime, List<ProductionTask> tasks, List<ProductionTask> currentResourcePreviousTasks) {
        log.debug("EE关系时间计算开始: taskCode={}", task.getCode());

        // 获取执行当前任务之前的结束时间最晚的任务
        PreTask preTask = getPreTask(task, resourcePreviousTask, previousEndTime, tasks);

        // 当前任务最早开始时间
        LocalDateTime earliestStartTime = null;
        // 任务最早开始时间所属任务，不是当前任务的前工序任务，计算任务最早开始时间
        if (ObjectNull.isNull(preTask.getEarliestStartTimeInProcessTask())) {
            earliestStartTime = calculateEarliestStartTimeWithPreInterval(task, currentResourcePreviousTasks, preTask.getEarliestStartTime());
            log.debug("EE关系-非工序任务前置: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
        } else {
            // 任务最早开始时间所属任务，是当前任务的前工序任务，计算任务最早开始时间
            ProductionTask frontTask = preTask.getEarliestStartTimeInProcessTask();
            Duration preTaskDuration = Duration.between(frontTask.getStartTime(), frontTask.getEndTime());
            Duration taskDuration = task.calculateTaskDuration();
            if (taskDuration == null) {
                log.warn("任务持续时间计算失败: taskCode={}", task.getCode());
                return null;
            }
            log.debug("EE关系工序任务分析: taskCode={}, frontTask={}, preTaskDuration={}, taskDuration={}",
                    task.getCode(), frontTask.getCode(), preTaskDuration, taskDuration);

            // 前短后长
            if (preTaskDuration.compareTo(taskDuration) < 0) {
                earliestStartTime = frontTask.getStartTime().plus(DurationUtils.convertDuration(task.getProcess().getBufferTime()));
                log.debug("EE关系-前短后长: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
            } else {
                // 前长后短
                LocalDateTime taskEndTime = frontTask.getEndTime().plus(DurationUtils.convertDuration(task.getProcess().getBufferTime()));
                earliestStartTime = taskEndTime.minus(taskDuration);
                earliestStartTime = frontTask.getStartTime().isBefore(earliestStartTime) ? earliestStartTime : preTask.getEarliestStartTime();
                log.debug("EE关系-前长后短: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
            }

        }

        // 计算开始时间
        earliestStartTime = calculateEarliestStartTimeWithPreInterval(task, currentResourcePreviousTasks, earliestStartTime);
        log.debug("EE关系时间计算完成: taskCode={}, result={}", task.getCode(), earliestStartTime);
        return earliestStartTime;
    }

    /**
     * 获取执行当前任务之前的结束时间最晚的任务
     *
     * @param task                 当前任务（待计算开始时间的任务）
     * @param resourcePreviousTask 当前任务所在资源的前一个任务
     * @param previousEndTime      当前任务所在资源的前一个任务的结束时间
     * @param tasks 所有排程任务
     * @return 当前任务开始时间
     */
    private PreTask getPreTask(ProductionTask task,
                               ProductionTask resourcePreviousTask,
                               LocalDateTime previousEndTime,
                               List<ProductionTask> tasks) {
        log.debug("获取前置任务: taskCode={}, resourcePreviousTask={}, previousEndTime={}",
                task.getCode(),
                resourcePreviousTask != null ? resourcePreviousTask.getCode() : "null",
                previousEndTime);

        // 当前任务最早开始时间
        LocalDateTime earliestStartTime = null;
        // 任务最早开始时间所属前工序任务
        ProductionTask earliestStartTimeInProcessTask = null;
        // 没有前置任务
        if (ObjectNull.isNull(task.getFrontTaskCodes())) {
            earliestStartTime = previousEndTime;
            log.debug("无前置任务，使用previousEndTime: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
        } else {
            // 有前置任务
            List<ProductionTask> frontProductionTasks = tasks.stream()
                    .filter(job -> task.getFrontTaskCodes().contains(job.getCode()))
                    .toList();
            log.debug("找到前置任务列表: taskCode={}, frontTasksCount={}", task.getCode(), frontProductionTasks.size());

            Optional<ProductionTask> optionalProductionTask = frontProductionTasks.stream()
                    .filter(job -> ObjectNull.isNotNull(job.getStartTime()))
                    .max(Comparator.comparing(job -> calculateEarliestStartTimeAfterCompletion(job, job.getEndTime())));
            if (optionalProductionTask.isPresent()) {
                // 最晚结束时间的前工序任务
                ProductionTask previousProcessTask = optionalProductionTask.get();
                log.debug("找到最晚结束的前置任务: taskCode={}, latestFrontTask={}", task.getCode(), previousProcessTask.getCode());

                // 前置任务未分配，则直接返回
                if (ObjectNull.isNotNull(previousProcessTask.getEndTime())) {
                    LocalDateTime frontMaxEarliestStartTime = calculateEarliestStartTimeAfterCompletion(previousProcessTask, previousProcessTask.getEndTime());
                    LocalDateTime taskInResourceEarliestStartTime = previousEndTime;
                    if (ObjectNull.isNotNull(resourcePreviousTask) && task.getFrontTaskCodes().contains(resourcePreviousTask.getCode()) && !task.getStartTask()) {
                        taskInResourceEarliestStartTime = calculateEarliestStartTimeAfterCompletion(resourcePreviousTask, previousEndTime);
                    }
                    log.debug("时间比较: frontMaxEarliestStartTime={}, taskInResourceEarliestStartTime={}",
                            frontMaxEarliestStartTime, taskInResourceEarliestStartTime);

                    if (frontMaxEarliestStartTime.isAfter(taskInResourceEarliestStartTime)) {
                        earliestStartTime = frontMaxEarliestStartTime;
                        earliestStartTimeInProcessTask = task.getStartTask() ? null : previousProcessTask;
                        log.debug("选择前置任务时间作为基准: taskCode={}, selectedTime={}, processTask={}",
                                task.getCode(), earliestStartTime,
                                earliestStartTimeInProcessTask != null ? earliestStartTimeInProcessTask.getCode() : "null");
                    } else {
                        earliestStartTime = taskInResourceEarliestStartTime;
                        log.debug("选择资源任务时间作为基准: taskCode={}, selectedTime={}", task.getCode(), earliestStartTime);
                    }
                }
            } else {
                earliestStartTime = previousEndTime;
                log.debug("无有效前置任务，使用previousEndTime: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);
            }
        }

        PreTask result = new PreTask()
                .setEarliestStartTime(earliestStartTime)
                .setEarliestStartTimeInProcessTask(earliestStartTimeInProcessTask);
        log.debug("前置任务分析完成: taskCode={}, resultTime={}, resultProcessTask={}",
                task.getCode(), earliestStartTime,
                earliestStartTimeInProcessTask != null ? earliestStartTimeInProcessTask.getCode() : "null");
        return result;
    }

    /**
     * 计算任务可能的最早开始时间.
     * 基于前一个任务的结束时间加上配置的后间隔时间
     *
     * @param preTask         前置任务
     * @param previousEndTime 前置任务最大结束时间
     * @return 当前任务可能的最早开始时间
     */
    private LocalDateTime calculateEarliestStartTimeAfterCompletion(ProductionTask preTask, LocalDateTime previousEndTime) {
        if (ObjectNull.isNull(preTask)) {
            return previousEndTime;
        }
        LocalDateTime startTime = previousEndTime;
        log.debug("计算后置任务时间: preTask={}, previousEndTime={}", preTask.getCode(), previousEndTime);

        // 根据工序配置计算当前任务可能的最早开始时间
        ProcessRouteNodePropertiesDTO process = preTask.getProcess();
        // 加上前置任务的后间隔时长
        Duration duration = DurationUtils.convertDuration(process.getPostIntervalDuration());
        if (ObjectNull.isNotNull(duration)) {
            if (startTime != null)
            startTime = startTime.plus(duration);
            log.debug("添加后置间隔时间: preTask={}, duration={}, newStartTime={}", preTask.getCode(), duration, startTime);
        }
        return startTime;
    }

    /**
     * 计算任务最早开始时间
     * 基于任务的最早可开始时间加上当前任务的前间隔时间
     *
     * @param task              当前任务
     * @param earliestStartTime 当前任务可能的最早开始时间
     * @return 任务最早开始时间
     */
    private LocalDateTime calculateEarliestStartTimeWithPreInterval(ProductionTask task, List<ProductionTask> currentResourcePreviousTasks, LocalDateTime earliestStartTime) {
        if (ObjectNull.isNull(task)) {
            return earliestStartTime;
        }
        if (ObjectNull.isNull(earliestStartTime)) {
            return earliestStartTime;
        }

        LocalDateTime startTime = earliestStartTime;
        log.debug("计算前置任务时间: taskCode={}, earliestStartTime={}", task.getCode(), earliestStartTime);

        // 根据工序配置计算当前任务可能的最早开始时间
        ProcessRouteNodePropertiesDTO process = task.getProcess();

        // 加上前置任务的后间隔时长
        Duration duration = DurationUtils.convertDuration(process.getPreIntervalDuration());
        if (ObjectNull.isNotNull(duration)) {
            startTime = startTime.plus(duration);
            log.debug("添加前置间隔时间: taskCode={}, duration={}, newStartTime={}", task.getCode(), duration, startTime);
        }

        // 加上最大等待物料齐套时长
        if (ObjectNull.isNotNull(task.getMaterialDelayMap())) {
            Set<String> previousMaterialIds = currentResourcePreviousTasks.stream()
                    .filter(t -> ObjectNull.isNotNull(t.getMaterialDelayMap()))
                    .map(t -> t.getMaterialDelayMap().keySet())
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
            Optional<Duration> maxDelayTimeOptional = task.getMaterialDelayMap().entrySet().stream()
                    .filter(material -> !previousMaterialIds.contains(material.getKey()))
                    .map(Map.Entry::getValue)
                    .max(Comparator.naturalOrder());
            if (maxDelayTimeOptional.isPresent()) {
                startTime = startTime.plus(maxDelayTimeOptional.get());
                log.debug("添加物料延迟时间: taskCode={}, delayTime={}, newStartTime={}", task.getCode(), maxDelayTimeOptional.get(), startTime);
            }
        }

        log.debug("前置任务时间计算完成: taskCode={}, finalStartTime={}", task.getCode(), startTime);
        return startTime;
    }

    /**
     * 任务时间缓存类
     */
    @Data
    @Accessors(chain = true)
    private static class TaskTimeCache {
        private LocalDateTime startTime;
        private long timestamp;

        public TaskTimeCache(LocalDateTime startTime, long timestamp) {
            this.startTime = startTime;
            this.timestamp = timestamp;
        }
    }

    /**
     * 验证结果类
     */
    @Data
    @Accessors(chain = true)
    public static class ValidationResult {
        private List<Violation> violations = new ArrayList<>();

        public enum Severity {
            INFO, WARNING, ERROR
        }

        @Data
        @Accessors(chain = true)
        public static class Violation {
            private String message;
            private Severity severity;

            public Violation(String message, Severity severity) {
                this.message = message;
                this.severity = severity;
            }
        }

        public void addViolation(String message, Severity severity) {
            violations.add(new Violation(message, severity));
        }

        public boolean hasErrors() {
            return violations.stream().anyMatch(v -> v.getSeverity() == Severity.ERROR);
        }

        public boolean hasWarnings() {
            return violations.stream().anyMatch(v -> v.getSeverity() == Severity.WARNING);
        }
    }

    @Data
    @Accessors(chain = true)
    public static class PreTask {
        // 当前任务最早开始时间
        private LocalDateTime earliestStartTime;
        // 任务最早开始时间所属前工序任务
        private ProductionTask earliestStartTimeInProcessTask;
    }
}
