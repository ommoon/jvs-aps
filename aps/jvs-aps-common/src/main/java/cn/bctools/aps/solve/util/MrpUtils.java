package cn.bctools.aps.solve.util;

import cn.bctools.aps.graph.Graph;
import cn.bctools.aps.graph.GraphEdge;
import cn.bctools.aps.graph.GraphNode;
import cn.bctools.aps.solve.model.*;
import cn.bctools.common.utils.ObjectNull;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * @author jvs
 * 物料需求计划
 */
public class MrpUtils {
    private MrpUtils() {
    }

    /**
     * 物料齐套计算
     * 1. 从BOM顶层往下依次检查是否缺料。 直到没有欠料的层级
     * 2. 最终生成一个有向无环图结构的物料需求清单
     *    - 点：欠料物料
     *    - 线：物料依赖关系（入度：上层物料，出度：下层物料）
     * 3. 生成计划时，可根据此图结构，构造任务依赖
     *
     * @param materialConstrained true-约束物料, false-不约束物料
     * @param order 订单
     * @param basicData 基础数据
     * @return 物料齐套计算结果（若启用约束物料，则计算结果为欠料信息；否则为生产订单物料需求信息）
     */
    public static Graph<MrpMaterial> calculateMaterialAvailability(boolean materialConstrained, ProductionOrder order, BasicData basicData) {
        // 1. 初始化空的MRP图结构
        Graph<MrpMaterial> mrpGraph = new Graph<>();

        // 2. 构造订单物料栈数据（顶层物料）
        MaterialStack orderMaterial = buildMaterialStack(order.getMaterialId(), order.getQuantity(), null);

        // 3. 如果不约束物料，直接将订单需求加入MRP图并返回
        if (!materialConstrained) {
            addMrpMaterial(mrpGraph, orderMaterial, null);
            return mrpGraph;
        }

        // 4. 获取基础数据
        Map<String, Material> materialMap = basicData.getMaterialMap();           // 物料信息映射
        Map<String, List<BomMaterial>> bomMap = basicData.getBomMap();            // BOM结构映射
        Map<String, List<IncomingMaterialOrder>> incomingMaterialOrderMap = basicData.getIncomingMaterialOrderMap(); // 在途库存映射

        // 5. 使用栈结构进行BOM逐层分解
        ArrayDeque<MaterialStack> materialStack = new ArrayDeque<>();
        materialStack.push(orderMaterial);

        // 6. 循环处理栈中物料，直到栈为空
        while (!materialStack.isEmpty()) {
            // 6.1 弹出栈顶物料
            MaterialStack stack = materialStack.pop();
            MrpMaterial mrpMaterial = stack.getMrpMaterial();
            Material material = materialMap.get(mrpMaterial.getId());

            // 6.2 扣减库存，计算缺料数量
            // 这里会先扣减现有库存，再扣减在途库存（根据交货时间）
            // TODO omm 2025/9/8 库存删减，单位运算，回写和信息保存
            BigDecimal lackQuantity = InventoryUtils.deductInventory(order, material, mrpMaterial.getQuantity(), incomingMaterialOrderMap);
            mrpMaterial.setQuantity(lackQuantity);

            // 6.3 如果存在缺料（缺料数量大于0）
            if (lackQuantity.compareTo(BigDecimal.ZERO) > 0) {
                // 6.3.1 将缺料物料添加到MRP图中
                addMrpMaterial(mrpGraph, stack, stack.getParentId());

                // 6.3.2 获取该物料的BOM子件列表
                List<BomMaterial> bomMaterialList = Optional.ofNullable(bomMap.get(mrpMaterial.getId()))
                        .orElseGet(ArrayList::new);

                // 6.3.3 遍历子件物料，计算需求数量并压入栈中
                bomMaterialList.forEach(bomMaterial -> {
                    // 计算子件需求数量 = 子件单耗 * 父件缺料数量
                    BigDecimal quantity = bomMaterial.getQuantity().multiply(lackQuantity).setScale(6, RoundingMode.HALF_UP);
                    MaterialStack childMaterial = buildMaterialStack(bomMaterial.getMaterialId(), quantity, stack.getId());
                    materialStack.push(childMaterial);
                });
            }
        }

        // 7. 返回MRP图结构
        return mrpGraph;
    }

    /**
     * 添加MRP物料需求
     *
     * @param mrpGraph mrp物料需求计算结果
     * @param mrpMaterialStack mrp物料需求
     * @param parentId 上层物料节点id
     */
    private static void addMrpMaterial(Graph<MrpMaterial> mrpGraph, MaterialStack mrpMaterialStack, String parentId) {
        GraphNode<MrpMaterial> node = new GraphNode<>();
        node.setId(mrpMaterialStack.getId());
        node.setData(mrpMaterialStack.getMrpMaterial());
        mrpGraph.addNode(node);
        // 添加线
        if (ObjectNull.isNotNull(parentId)) {
            GraphEdge edge = new GraphEdge()
                    .setSource(node.getId())
                    .setTarget(parentId);
            mrpGraph.addEdge(edge);
        }
    }


    /**
     * 构造物料栈数据
     *
     * @param materialId 物料id
     * @param quantity 需求数量
     * @param parentId 上层物料
     * @return 物料信息
     */
    private static MaterialStack buildMaterialStack(String materialId, BigDecimal quantity, String parentId) {
        MrpMaterial mrpMaterial = new MrpMaterial()
                .setId(materialId)
                .setQuantity(quantity);
        return new MaterialStack()
                .setId(IdWorker.getIdStr())
                .setMrpMaterial(mrpMaterial)
                .setParentId(parentId);
    }

    @Data
    @Accessors(chain = true)
    private static class MaterialStack {
        /**
         * mrp物料需求
         */
        private MrpMaterial mrpMaterial;

        /**
         * 节点id
         */
        private String id;

        /**
         * 上层节点id
         */
        private String parentId;
    }


}
