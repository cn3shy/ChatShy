package io.github.cn3shy.chatshy.core;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.mapper.ModelPriceEntity;
import io.github.cn3shy.chatshy.mapper.ModelPriceMapper;

/**
 * 模型单价表：按「每百万 token」计价，用于用量页的资金消耗估算。
 * 数据访问走 MyBatis-Plus（ModelPriceMapper）。
 */
@Service
public class ModelPriceStore {
    /** 对外 API 用的不可变形状。 */
    public record ModelPrice(String id, String model, BigDecimal inputPerM, BigDecimal outputPerM,
                             BigDecimal cacheReadPerM, String currency, long createdAt, long updatedAt) {
    }

    public record PriceInput(String model, BigDecimal inputPerM, BigDecimal outputPerM,
                             BigDecimal cacheReadPerM, String currency) {
    }

    /** null = 字段缺省（保持不变）。 */
    public record PricePatch(String model, BigDecimal inputPerM, BigDecimal outputPerM,
                             BigDecimal cacheReadPerM, String currency) {
    }

    public static class PriceValidationException extends RuntimeException {
        private final String code;

        public PriceValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final int MODEL_MAX = 200;
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    private final ModelPriceMapper mapper;

    public ModelPriceStore(ModelPriceMapper mapper) {
        this.mapper = mapper;
    }

    public List<ModelPrice> listPrices() {
        return mapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelPriceEntity>()
                                .orderByAsc(ModelPriceEntity::getModel))
                .stream().map(ModelPriceStore::toRecord).toList();
    }

    public ModelPrice getPrice(String id) {
        ModelPriceEntity entity = mapper.selectById(id);
        return entity == null ? null : toRecord(entity);
    }

    /** 按模型名精确匹配取价；没有配置时返回 null（该模型的请求不计花费）。 */
    public ModelPrice findByModel(String model) {
        ModelPriceEntity entity = mapper.findByModel(model);
        return entity == null ? null : toRecord(entity);
    }

    public ModelPrice createPrice(PriceInput input) {
        String model = normalizeModel(input.model());
        assertModelFree(model, null);
        BigDecimal in = normalizePrice(input.inputPerM(), "input_per_m");
        BigDecimal out = normalizePrice(input.outputPerM(), "output_per_m");
        BigDecimal cache = normalizePrice(input.cacheReadPerM(), "cache_read_per_m");
        String currency = normalizeCurrency(input.currency());
        long now = System.currentTimeMillis();
        ModelPriceEntity entity = new ModelPriceEntity();
        entity.setModel(model);
        entity.setInputPerM(in);
        entity.setOutputPerM(out);
        entity.setCacheReadPerM(cache);
        entity.setCurrency(currency);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return getPrice(entity.getId());
    }

    public ModelPrice updatePrice(String id, PricePatch patch) {
        ModelPriceEntity current = mapper.selectById(id);
        if (current == null) return null;
        String model = patch.model() == null ? current.getModel() : normalizeModel(patch.model());
        BigDecimal in = patch.inputPerM() == null ? current.getInputPerM()
                : normalizePrice(patch.inputPerM(), "input_per_m");
        BigDecimal out = patch.outputPerM() == null ? current.getOutputPerM()
                : normalizePrice(patch.outputPerM(), "output_per_m");
        BigDecimal cache = patch.cacheReadPerM() == null ? current.getCacheReadPerM()
                : normalizePrice(patch.cacheReadPerM(), "cache_read_per_m");
        String currency = patch.currency() == null ? current.getCurrency()
                : normalizeCurrency(patch.currency());
        assertModelFree(model, id);
        ModelPriceEntity update = new ModelPriceEntity();
        update.setId(id);
        update.setModel(model);
        update.setInputPerM(in);
        update.setOutputPerM(out);
        update.setCacheReadPerM(cache);
        update.setCurrency(currency);
        update.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(update);
        return getPrice(id);
    }

    public boolean deletePrice(String id) {
        return mapper.deleteById(id) > 0;
    }

    /** 按 token 数计算花费；单价为 null 或 token 为 null/负数时返回 null。 */
    public static BigDecimal cost(BigDecimal perMillion, Long tokens) {
        if (perMillion == null || tokens == null || tokens < 0) return null;
        if (perMillion.signum() == 0) return BigDecimal.ZERO;
        // tokens * perMillion / 1e6，保留 6 位小数足够展示
        return new BigDecimal(tokens).multiply(perMillion)
                .divide(new BigDecimal(1_000_000), 6, java.math.RoundingMode.HALF_UP);
    }

    static ModelPrice toRecord(ModelPriceEntity entity) {
        return new ModelPrice(entity.getId(), entity.getModel(), entity.getInputPerM(),
                entity.getOutputPerM(), entity.getCacheReadPerM(), entity.getCurrency(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String normalizeModel(String raw) {
        if (raw == null) throw new PriceValidationException("invalid_model", "模型名不能为空");
        String model = raw.trim();
        if (model.isEmpty()) throw new PriceValidationException("invalid_model", "模型名不能为空");
        if (model.length() > MODEL_MAX) throw new PriceValidationException("invalid_model", "模型名过长");
        return model;
    }

    private static BigDecimal normalizePrice(BigDecimal raw, String field) {
        if (raw == null) throw new PriceValidationException("invalid_" + field, "单价必须是数字");
        if (raw.signum() < 0) throw new PriceValidationException("invalid_" + field, "单价不能为负数");
        if (raw.compareTo(MAX_PRICE) > 0) throw new PriceValidationException("invalid_" + field, "单价过大");
        return raw.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) return "CNY";
        String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"CNY".equals(currency) && !"USD".equals(currency)) {
            throw new PriceValidationException("invalid_currency", "币种只支持 CNY / USD");
        }
        return currency;
    }

    private void assertModelFree(String model, String exceptId) {
        ModelPriceEntity existing = mapper.findByModel(model);
        if (existing != null && (exceptId == null || !exceptId.equals(existing.getId()))) {
            throw new PriceValidationException("price_exists", "已存在模型「" + model + "」的单价配置");
        }
    }
}
