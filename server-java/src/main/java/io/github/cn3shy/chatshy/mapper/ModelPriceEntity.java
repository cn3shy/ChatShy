package io.github.cn3shy.chatshy.mapper;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** model_prices 表实体：模型单价（每百万 token）。 */
@TableName("model_prices")
public class ModelPriceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String model;

    private BigDecimal inputPerM;

    private BigDecimal outputPerM;

    private BigDecimal cacheReadPerM;

    private String currency;

    private Long createdAt;

    private Long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public BigDecimal getInputPerM() {
        return inputPerM;
    }

    public void setInputPerM(BigDecimal inputPerM) {
        this.inputPerM = inputPerM;
    }

    public BigDecimal getOutputPerM() {
        return outputPerM;
    }

    public void setOutputPerM(BigDecimal outputPerM) {
        this.outputPerM = outputPerM;
    }

    public BigDecimal getCacheReadPerM() {
        return cacheReadPerM;
    }

    public void setCacheReadPerM(BigDecimal cacheReadPerM) {
        this.cacheReadPerM = cacheReadPerM;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
