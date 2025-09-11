package cn.bctools.aps.solve.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @author jvs
 * MRP物料需求信息
 */
@Data
@Accessors(chain = true)
public class MrpMaterial {
    /**
     * 物料id
     */
    private String id;
    /**
     * 需求数量
     */
    private BigDecimal quantity;

    private String materialCode;

    private String materialName;

    /**
     * 原物料剩余库存
     */
    private BigDecimal stockQuantity;
    /**
     * 原物料扣减库存
     */
    private BigDecimal deductQuantity;

    /**
     * 额外订单扣减库存
     */
    private Map<String, BigDecimal> extraDeductQuantity;
    /**
     * 额外订单剩余库存
     */
    private Map<String, BigDecimal> extraStockQuantity;

}
