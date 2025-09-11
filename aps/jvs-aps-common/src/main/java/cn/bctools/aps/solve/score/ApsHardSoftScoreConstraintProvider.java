package cn.bctools.aps.solve.score;

import cn.bctools.aps.solve.model.ProductionOrder;
import cn.bctools.aps.solve.model.ProductionTask;
import cn.bctools.common.utils.ObjectNull;
import lombok.extern.slf4j.Slf4j;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.score.stream.*;
import org.optaplanner.core.api.score.stream.bi.BiConstraintStream;
import org.optaplanner.core.api.score.stream.uni.UniConstraintStream;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 常用 Joiners 类型
 * Joiners.equal(): 连接具有相同属性值的实体
 * Joiners.lessThan(): 连接第一个实体属性值小于第二个实体属性值的实体对
 * Joiners.greaterThan(): 连接第一个实体属性值大于第二个实体属性值的实体对
 * Joiners.overlapping(): 连接时间范围有重叠的实体对
 *
 * forEach(ProductionTask.class)：遍历所有生产任务
 * join(ProductionTask.class, ...)：将每个任务与其它任务进行连接
 * Joiners.equal(ProductionTask::getResource)：只连接在同一资源上的任务对
 * Joiners.lessThan(ProductionTask::getPriority)：只连接优先级不同的任务对，其中第二个任务优先级更低
 * 这样就创建了所有在同一资源上优先级不同的任务对，然后通过过滤器和惩罚函数确保高优先级任务先执行
 * 约束 - 定义OptaPlanner优化过程中的各种约束条件
 * penalize 用于惩罚不良行为
 * reward 用于奖励良好行为
 * impact 提供最大的灵活性来动态决定分数影响
 */
@Slf4j
public class ApsHardSoftScoreConstraintProvider implements ConstraintProvider {

    private static final AtomicInteger atomicLong = new AtomicInteger(0);

    /**
     * 定义所有约束条件
     * @param constraintFactory 约束工厂
     * @return 约束数组
     */
    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                // 硬约束优先
                equipmentCanOnlyHandleMatchingProcesses(constraintFactory),     // 主资源只能处理匹配的工序
                theEquipmentCanOnlyProcessOneProcessAtTheSameTime(constraintFactory), // 每个任务都要分配资源
                noResourceTimeOverlapping(constraintFactory),                   // 资源时间不重叠
                // deliveryDelayPenalty(constraintFactory),                       // 延迟交付惩罚

                // 软约束
                finishEarly(constraintFactory),                                 // 尽早完成
                minimizeGapBetweenDependentTasks(constraintFactory),            // 最小化依赖任务间的间隙
                prioritizeMainOrdersByPriority(constraintFactory),              // 主订单优先级高的任务应该优先完成
                arrangeByPriority(constraintFactory),                           // 尽量按优先级安排
        };
    }

    /**
     * 按主订单优先级安排任务
     * 软约束：主订单优先级高的任务应该优先完成
     */
    public Constraint prioritizeMainOrdersByPriority(ConstraintFactory factory) {
        return factory.forEach(ProductionTask.class)
                .join(ProductionTask.class,
                        Joiners.lessThan(task ->
                                Optional.ofNullable(task.getOrder())
                                        .map(ProductionOrder::getDeliveryTime)
                                        .orElse(LocalDateTime.now())))
                .filter((task1, task2) ->
                        ObjectNull.isNotNull(task1.getStartTime()) &&
                                ObjectNull.isNotNull(task2.getStartTime()) &&
                                ObjectNull.isNull(task1.getMergeTaskCode()) &&
                                ObjectNull.isNull(task2.getMergeTaskCode()))
                // 如果优先级高的订单任务开始时间晚于优先级低的订单任务，则违反约束
                .filter((task1, task2) -> task1.getStartTime().isAfter(task2.getStartTime()))
                .penalizeLong(HardSoftLongScore.ONE_SOFT, (task1, task2) -> {
                    // 基于订单优先级差异计算惩罚值
                    int priority1 = Optional.ofNullable(task1.getOrder())
                            .map(ProductionOrder::getPriority)
                            .orElse(Integer.MAX_VALUE);
                    int priority2 = Optional.ofNullable(task2.getOrder())
                            .map(ProductionOrder::getPriority)
                            .orElse(Integer.MAX_VALUE);
                    long priorityDifference = (long) (priority2 - priority1);
                    return priorityDifference * priorityDifference * 10000L;
                })
                .asConstraint("按订单优先级优先完成主订单任务");
    }


    /**
     * 尽量按task优先级安排
     * 软约束：同一个资源优先级高的任务应该优先安排
     */
    public Constraint arrangeByPriority(ConstraintFactory factory) {
        BiConstraintStream<ProductionTask, ProductionTask> taskList = factory.forEach(ProductionTask.class)
                // 与同一资源上优先级更低的任务连接
                .join(ProductionTask.class,
                        Joiners.equal(ProductionTask::getResource),  // 同一个资源
                        Joiners.lessThan(ProductionTask::getPriority)  // 优先级更低
                );
        log.info("同一个订单优先级高的任务应该优先安排在同一资源上 atomicLong= {}", atomicLong.incrementAndGet());
        return taskList
                // 过滤未锁定且有开始时间的任务
                .filter((task1, task2) -> !task1.getPinned() && !task2.getPinned())
                .filter((task1, task2) -> ObjectNull.isNotNull(task1.getStartTime()) && ObjectNull.isNotNull(task2.getStartTime()))
                .filter((task1, task2) -> ObjectNull.isNull(task1.getMergeTaskCode()) && ObjectNull.isNull(task2.getMergeTaskCode()))
                // 如果高优先级任务开始时间晚于低优先级任务，则违反约束
                .filter((task1, task2) -> task1.getStartTime().isAfter(task2.getStartTime()))
                // 惩罚违反约束的情况
                .penalizeLong(HardSoftLongScore.ONE_SOFT, (task1, task2) -> {
                    log.info("优先级高的任务应该优先安排在同一资源上 task1= {}, task2= {}", task1, task2);
                    // 增加优先级差异的权重，确保优先级高的任务先执行
                    long priorityDifference = (long) (task2.getPriority() - task1.getPriority());
                    // 使用平方函数放大优先级差异的影响
                    return Math.toIntExact(Math.abs(priorityDifference)) * 1000L;
                })
                .asConstraint("尽量按优先级安排");
    }

    /**
     * 尽早完成任务
     * 软约束：应该尽早完成所有任务
     */
    public Constraint finishEarly(ConstraintFactory factory) {
        UniConstraintStream<ProductionTask> taskList = factory.forEach(ProductionTask.class);
        log.info("应该尽早完成所有任务 atomicLong= {}", atomicLong.incrementAndGet());
        return taskList
                // 过滤有开始和结束时间且非合并任务的任务
                .filter(task -> task.getStartTime() != null && task.getEndTime() != null && ObjectNull.isNull(task.getMergeTaskCode()))
                // 按最大结束时间分组
                .groupBy(ConstraintCollectors.max(ProductionTask::getEndTime))
                // 惩罚违反约束的情况
                .penalizeLong(HardSoftLongScore.ONE_SOFT, maxEndTime -> maxEndTime.toEpochSecond(ZoneOffset.UTC))
                .asConstraint("尽早完成任务");
    }

    /**
     * 避免时间冲突
     * 软约束：应该最小化依赖任务间的间隙
     */
    public Constraint minimizeGapBetweenDependentTasks(ConstraintFactory factory) {
        UniConstraintStream<ProductionTask> taskList = factory.forEach(ProductionTask.class);
        log.info("应该最小化依赖任务间的间隙 atomicLong= {}", atomicLong.incrementAndGet());
        return taskList
                // 过滤有开始和结束时间且非合并任务的任务
                .filter(task -> task.getStartTime() != null && task.getEndTime() != null && ObjectNull.isNull(task.getMergeTaskCode()))
                .filter(task -> {
                    // 过滤有前置任务的任务
                    if (ObjectNull.isNull(task.getFrontTasks())) {
                        return false;
                    }
                    return task.getFrontTasks().stream().noneMatch(t -> ObjectNull.isNull(t.getStartTime()));
                })
                // 惩罚违反约束的情况
                .penalizeLong(HardSoftLongScore.ONE_HARD, (task) -> {
                    log.info("应该最小化依赖任务间的间隙 task1= {}", task);
                    LocalDateTime frontTaskMaxEndTime = task.getFrontTasks().stream().map(ProductionTask::getEndTime).max(Comparator.naturalOrder()).get();
                    Duration duration = Duration.between(frontTaskMaxEndTime, task.getStartTime());
                    return duration.toMinutes() > 0 ? duration.toMinutes() : 0;
                })
                .asConstraint("优化任务调度的连续性");
    }

    /**
     * 主资源只能处理匹配的工序
     * 硬约束：主资源只能处理匹配的工序
     */
    public Constraint equipmentCanOnlyHandleMatchingProcesses(ConstraintFactory factory) {
        return factory.forEach(ProductionTask.class)
                .filter(productionJob -> ObjectNull.isNotNull(productionJob.getResource()))
                .filter(productionJob -> ObjectNull.isNotNull(productionJob.getProcess()))
                .filter(productionJob -> productionJob.getProcess().getUseMainResources() != null)
                // 如果任务所需资源与实际分配资源不匹配，则违反约束
                .filter(productionJob -> productionJob.getProcess().getUseMainResources()
                        .stream()
                        .noneMatch(resource ->
                                ObjectNull.isNotNull(resource) &&
                                        ObjectNull.isNotNull(productionJob.getResource()) &&
                                        ObjectNull.isNotNull(resource.getId()) &&
                                        ObjectNull.isNotNull(productionJob.getResource().getId()) &&
                                        productionJob.getResource().getId().equals(resource.getId())))
                // 惩罚违反约束的情况（硬约束）
                .penalize(HardSoftLongScore.ONE_HARD, task -> {
                    log.warn("任务被分配到不兼容的资源: taskCode={}, assignedResource={}, requiredResources={}",
                            task,
                            task.getResource().getCode(),
                            task.getProcess().getUseMainResources().stream()
                                    .filter(ObjectNull::isNotNull)
                                    .map(r -> r.getThroughput() + "(" + r.getId() + ")")
                                    .collect(Collectors.toList()));
                    return Integer.MAX_VALUE / 1000;
                })
                .asConstraint("主资源只能处理匹配的工序");
    }

    /**
     * 每个任务都要分配资源
     * 硬约束：每个任务都要分配资源
     */
    public Constraint theEquipmentCanOnlyProcessOneProcessAtTheSameTime(ConstraintFactory factory) {
        UniConstraintStream<ProductionTask> taskList = factory.forEach(ProductionTask.class);
        log.info("每个任务都要分配资源 atomicLong= {}", atomicLong.incrementAndGet());
        return taskList
                // 过滤未分配资源或无开始/结束时间的任务
                .filter(job -> job.getResource() == null || job.getStartTime() == null || job.getEndTime() == null)
                // 惩罚违反约束的情况（硬约束）
                .penalize(HardSoftLongScore.ONE_HARD)
                .asConstraint("每个任务都要分配资源");
    }

    /**
     * 资源不能同时处理多个任务
     * 硬约束：资源不能同时处理多个任务
     */
    public Constraint noResourceTimeOverlapping(ConstraintFactory factory) {
        UniConstraintStream<ProductionTask> taskList = factory.forEach(ProductionTask.class);
        log.info("资源不能同时处理多个任务 atomicLong= {}", atomicLong.incrementAndGet());
        return taskList
                // 与同一资源上时间重叠的任务连接
                .join(ProductionTask.class,
                        Joiners.equal(ProductionTask::getResource),
                        Joiners.overlapping(
                                task -> Optional.ofNullable(task.getStartTime()).orElseGet(() -> LocalDateTime.MIN),
                                task -> Optional.ofNullable(task.getEndTime()).orElseGet(() -> LocalDateTime.MAX))
                )
                // 过滤非合并任务且有开始时间的任务
                .filter((task1, task2) -> ObjectNull.isNull(task1.getMergeTaskCode()) && ObjectNull.isNull(task2.getMergeTaskCode()))
                .filter((task1, task2) -> ObjectNull.isNotNull(task1.getStartTime()) && ObjectNull.isNotNull(task2.getStartTime()))
                .filter((task1, task2) -> {
                    // 过滤相同任务和真正时间重叠的任务
                    if (task1.getCode().equals(task2.getCode())) {
                        return false;
                    }
                    return task1.getEndTime().isAfter(task2.getStartTime()) && task1.getStartTime().isBefore(task2.getEndTime());
                })
                // 惩罚违反约束的情况（硬约束）
                .penalize(HardSoftLongScore.ONE_HARD, ApsHardSoftScoreConstraintProvider::getMinuteOverlap)
                .asConstraint("资源没有任务时间重叠");
    }

    /**
     * 获取冲突分钟数
     *
     * @param leftTask  左任务
     * @param rightTask 右任务
     * @return 冲突分钟数
     */
    private static int getMinuteOverlap(ProductionTask leftTask, ProductionTask rightTask) {
        LocalDateTime leftStart = leftTask.getStartTime();
        LocalDateTime leftEnd = leftTask.getEndTime();
        LocalDateTime rightStart = rightTask.getStartTime();
        LocalDateTime rightEnd = rightTask.getEndTime();

        // 计算重叠的开始和结束时间
        LocalDateTime start = leftStart.isAfter(rightStart) ? leftStart : rightStart;
        LocalDateTime end = leftEnd.isBefore(rightEnd) ? leftEnd : rightEnd;

        // 确保开始时间不晚于结束时间，否则没有重叠
        if (start.isAfter(end)) {
            return 0;
        }

        // 计算分钟数，确保非负
        long minutes = Duration.between(start, end).toMinutes();
        // 防止超过int范围
        return (int) Math.min(minutes, Integer.MAX_VALUE);
    }


}
