package io.github.cn3shy.chatshy.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** model_prices 的 MyBatis-Plus Mapper。 */
public interface ModelPriceMapper extends BaseMapper<ModelPriceEntity> {

    /** 按模型名精确取价。 */
    default ModelPriceEntity findByModel(String model) {
        if (model == null || model.isEmpty()) return null;
        List<ModelPriceEntity> rows = selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelPriceEntity>()
                        .eq(ModelPriceEntity::getModel, model));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
