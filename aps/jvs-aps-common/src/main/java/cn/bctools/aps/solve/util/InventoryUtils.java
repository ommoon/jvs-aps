package cn.bctools.aps.solve.util;

import cn.bctools.aps.solve.model.IncomingMaterialOrder;
import cn.bctools.aps.solve.model.Material;
import cn.bctools.aps.solve.model.MrpMaterial;
import cn.bctools.aps.solve.model.ProductionOrder;
import cn.bctools.common.exception.BusinessException;
import cn.bctools.common.utils.ObjectNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jvs
 * 库存工具
 */
public class InventoryUtils {
    private InventoryUtils() {
    }

    /**
     * 扣减库存
     *
     * @param order                    订单
     * @param material                 物料
     * @param mrpMaterial              物料需求数量
     * @param incomingMaterialOrderMap 来料订单
     * @return 缺料数量
     */
    public static BigDecimal deductInventory(ProductionOrder order, Material material, MrpMaterial mrpMaterial,
                                             Map<String, List<IncomingMaterialOrder>> incomingMaterialOrderMap) {

        if (ObjectNull.isNull(material)) {
            throw new BusinessException("物料不存在", order.getMaterialCode());
        }
        // 优先扣减在库库存
        CalculationInventoryResult stockInventory = deductInStockInventory(material, mrpMaterial.getQuantity());
        mrpMaterial.setStockQuantity(stockInventory.getStockQuantity());
        mrpMaterial.setDeductQuantity(stockInventory.getDeductQuantity());
        mrpMaterial.setMaterialCode(material.getCode());
        mrpMaterial.setMaterialName(material.getName());
        // 缺料数量
        BigDecimal lackQuantity = stockInventory.getLackQuantity();
        if (lackQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return lackQuantity;
        }
        // 在库库存不够，扣减在途库存（来料订单）
        return deductInTransitInventory(stockInventory, order, material, incomingMaterialOrderMap, mrpMaterial);
    }

    /**
     * 扣减在库库存
     *
     * @param material         物料
     * @param requiredQuantity 需要的库存数量
     * @return 缺料数量
     */
    private static CalculationInventoryResult deductInStockInventory(Material material, BigDecimal requiredQuantity) {
        // 扣减库存
        CalculationInventoryResult result = calculationInventory(material.getQuantity(), requiredQuantity);
        result.setId(material.getId());
        result.setType("stock");
        // TODO omm 2025/9/11 要全局实时查不然这里会有同种物料不同产品的场景计算
        // 更新原物料库存
        material.setQuantity(result.getDeductQuantity());
        // 返回缺料数量
        return result;
    }


    /**
     * 扣减在途库存
     *
     * @param stockInventory         需要的库存数量
     * @param order                    订单
     * @param material                 物料
     * @param incomingMaterialOrderMap 在途库存
     * @return 缺料数量
     */
    private static BigDecimal deductInTransitInventory(CalculationInventoryResult stockInventory, ProductionOrder order, Material material,
                                                       Map<String, List<IncomingMaterialOrder>> incomingMaterialOrderMap, MrpMaterial mrpMaterial) {
        // 来料订单库存
        // 缺料数量
        BigDecimal lackQuantity = stockInventory.getLackQuantity();
        if (lackQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return lackQuantity;
        }
        // 扣减库存
        List<IncomingMaterialOrder> incomingMaterialOrders = incomingMaterialOrderMap.get(material.getCode());
        if (ObjectNull.isNull(incomingMaterialOrders)) {
            return lackQuantity;
        }
        Map<String, BigDecimal> extraDeductQuantity = new HashMap<>(incomingMaterialOrders.size());
        Map<String, BigDecimal> extraStockQuantity = new HashMap<>(incomingMaterialOrders.size());
        incomingMaterialOrders = incomingMaterialOrders.stream()
                .sorted(Comparator.comparing(IncomingMaterialOrder::getDeliveryTime))
                .toList();
        // 获取可用在途库存
        for (IncomingMaterialOrder incomingMaterialOrder : incomingMaterialOrders) {
            // 无缺料，跳过
            if (lackQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 跳过无库存可分配的
            if (incomingMaterialOrder.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 跳过订单交期时间比在途库存预计入库时间晚的
            LocalDateTime deliverTime = incomingMaterialOrder.getDeliveryTime();
            if (ObjectNull.isNotNull(material.getBufferTimeDuration())) {
                deliverTime = deliverTime.plus(material.getBufferTimeDuration());
            }
            if (order.getDeliveryTime().isBefore(deliverTime)) {
                continue;
            }
            // 计算库存
            CalculationInventoryResult result = calculationInventory(incomingMaterialOrder.getQuantity(), lackQuantity);
            result.setId(incomingMaterialOrder.getId());
            result.setType("incoming");
            incomingMaterialOrder.setQuantity(result.getStockQuantity());
            lackQuantity = result.getLackQuantity();

            extraDeductQuantity.put(incomingMaterialOrder.getId(), result.getDeductQuantity());
            extraStockQuantity.put(incomingMaterialOrder.getId(), result.getStockQuantity());
        }
        mrpMaterial.setExtraDeductQuantity(extraDeductQuantity);
        return lackQuantity;
    }

    /**
     * 扣减库存计算
     *
     * @param materialQuantity 物料库存数量
     * @param requiredQuantity 需要的库存数量
     * @return 计算结果
     */
    private static CalculationInventoryResult calculationInventory(BigDecimal materialQuantity, BigDecimal requiredQuantity) {
        // 扣减的库存量
        BigDecimal deductQuantity = null;
        // 更新的库存量
        BigDecimal stockQuantity = null;
        // 缺料数量
        BigDecimal lackQuantity = BigDecimal.ZERO;
        CalculationInventoryResult result = new CalculationInventoryResult();
        if (materialQuantity.compareTo(requiredQuantity) >= 0) {
            stockQuantity = materialQuantity.subtract(requiredQuantity);
            deductQuantity = requiredQuantity;
        } else {
            lackQuantity = requiredQuantity.subtract(materialQuantity);
            deductQuantity = materialQuantity;
            stockQuantity = BigDecimal.ZERO;
        }
        result.setDeductQuantity(deductQuantity);
        result.setStockQuantity(stockQuantity);
        result.setLackQuantity(lackQuantity);
        return result;
    }

    /**
     * 库存计算结果
     */
    @Getter
    @Setter
    static class CalculationInventoryResult {

        private String id;

        private String type;
        // 扣减后的库存数量
        private BigDecimal deductQuantity;
        // 扣减后的库存数量
        private BigDecimal stockQuantity;
        // 缺料数量
        private BigDecimal lackQuantity;
    }
}
