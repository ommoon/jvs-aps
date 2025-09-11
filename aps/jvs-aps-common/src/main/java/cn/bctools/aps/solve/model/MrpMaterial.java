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

    /**
     * 原物料库存
     */
    private BigDecimal oriQuantity;

    /**
     * 来料订单库存
     */
    private Map<String, BigDecimal> inMaterialQuantity;

}
