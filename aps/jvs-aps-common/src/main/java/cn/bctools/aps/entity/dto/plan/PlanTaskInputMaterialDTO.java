package cn.bctools.aps.entity.dto.plan;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @author jvs
 */
@Data
@Accessors(chain = true)
@ApiModel("排程任务输入物料信息")
public class PlanTaskInputMaterialDTO {
    @ApiModelProperty(value = "物料id")
    private String id;

    // 工序上可能和BOM理论的数量不同，比如损耗量
    @ApiModelProperty(value = "物料数量", notes = "任务所需当前物料总数")
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
