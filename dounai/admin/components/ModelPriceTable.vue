<script setup lang="ts">
import { ref } from 'vue';
import { api } from '../api';
import type { ModelPrice, ModelPriceInput } from '../types';

/** 模型单价管理：表格 + 行内编辑 + 新增 + 删除。单价按「每百万 token」计。 */
const prices = ref<ModelPrice[]>([]);
const loading = ref(false);
const msg = ref('');
const busy = ref(false);

const newRow = ref<ModelPriceInput>({ model: '', inputPerM: 0, outputPerM: 0, cacheReadPerM: 0, currency: 'CNY' });
const adding = ref(false);

async function refresh(): Promise<void> {
  loading.value = true;
  try {
    const res = await api.modelPrices();
    prices.value = res.prices;
  } catch {
    msg.value = '单价列表加载失败';
  } finally {
    loading.value = false;
  }
}

function errText(err: unknown, fallback: string): string {
  const body = (err as { body?: { message?: string; error?: string } }).body;
  if (body?.message) return body.message;
  const codes: Record<string, string> = {
    price_exists: '该模型已配置单价',
    invalid_model: '模型名不能为空',
    invalid_currency: '币种只支持 CNY / USD',
    not_found: '该单价配置不存在，请刷新',
  };
  const code = body?.error ?? '';
  return codes[code] ?? fallback;
}

async function add(): Promise<void> {
  if (busy.value) return;
  busy.value = true;
  msg.value = '';
  try {
    await api.createModelPrice({ ...newRow.value, model: newRow.value.model.trim() });
    newRow.value = { model: '', inputPerM: 0, outputPerM: 0, cacheReadPerM: 0, currency: 'CNY' };
    adding.value = false;
    await refresh();
  } catch (err) {
    msg.value = errText(err, '新增失败');
  } finally {
    busy.value = false;
  }
}

async function saveRow(price: ModelPrice): Promise<void> {
  if (busy.value) return;
  busy.value = true;
  msg.value = '';
  try {
    await api.updateModelPrice(price.id, {
      model: price.model,
      inputPerM: price.inputPerM,
      outputPerM: price.outputPerM,
      cacheReadPerM: price.cacheReadPerM,
      currency: price.currency,
    });
    await refresh();
    msg.value = '已保存 ✓';
  } catch (err) {
    msg.value = errText(err, '保存失败');
    await refresh();
  } finally {
    busy.value = false;
  }
}

async function remove(price: ModelPrice): Promise<void> {
  if (!confirm(`删除模型「${price.model}」的单价？删除后该模型的请求不再计花费。`)) return;
  busy.value = true;
  msg.value = '';
  try {
    await api.deleteModelPrice(price.id);
    await refresh();
  } catch (err) {
    msg.value = errText(err, '删除失败');
  } finally {
    busy.value = false;
  }
}

function onNumberInput(row: ModelPrice | ModelPriceInput, key: 'inputPerM' | 'outputPerM' | 'cacheReadPerM', value: string): void {
  const n = Number(value);
  (row as Record<string, unknown>)[key] = Number.isFinite(n) && n >= 0 ? n : 0;
}

refresh();
</script>

<template>
  <div class="price-table">
    <p class="note muted">
      单价按「每百万 token」计；配了价的模型才会计入用量页的资金消耗。
    </p>
    <table class="usage-table">
      <thead>
        <tr>
          <th>模型</th>
          <th>输入 / M</th>
          <th>输出 / M</th>
          <th>缓存读 / M</th>
          <th>币种</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in prices" :key="p.id">
          <td><input v-model="p.model" class="cell-input" /></td>
          <td>
            <input
              class="cell-input num"
              :value="p.inputPerM"
              inputmode="decimal"
              @input="onNumberInput(p, 'inputPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <input
              class="cell-input num"
              :value="p.outputPerM"
              inputmode="decimal"
              @input="onNumberInput(p, 'outputPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <input
              class="cell-input num"
              :value="p.cacheReadPerM"
              inputmode="decimal"
              @input="onNumberInput(p, 'cacheReadPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <select v-model="p.currency" class="cell-select">
              <option value="CNY">CNY</option>
              <option value="USD">USD</option>
            </select>
          </td>
          <td class="row-actions">
            <button class="ghost-btn small" :disabled="busy" @click="saveRow(p)">保存</button>
            <button class="ghost-btn small danger" :disabled="busy" @click="remove(p)">删除</button>
          </td>
        </tr>
        <tr v-if="adding">
          <td><input v-model="newRow.model" class="cell-input" placeholder="模型名" /></td>
          <td>
            <input
              class="cell-input num"
              :value="newRow.inputPerM"
              inputmode="decimal"
              @input="onNumberInput(newRow, 'inputPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <input
              class="cell-input num"
              :value="newRow.outputPerM"
              inputmode="decimal"
              @input="onNumberInput(newRow, 'outputPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <input
              class="cell-input num"
              :value="newRow.cacheReadPerM"
              inputmode="decimal"
              @input="onNumberInput(newRow, 'cacheReadPerM', ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td>
            <select v-model="newRow.currency" class="cell-select">
              <option value="CNY">CNY</option>
              <option value="USD">USD</option>
            </select>
          </td>
          <td class="row-actions">
            <button class="primary-btn small" :disabled="busy || !newRow.model.trim()" @click="add">添加</button>
            <button class="ghost-btn small" @click="adding = false">取消</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="price-actions">
      <button v-if="!adding" class="ghost-btn small" :disabled="loading" @click="adding = true">
        + 新增单价
      </button>
      <button class="ghost-btn small" :disabled="loading" @click="refresh">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
      <span v-if="msg" class="note">{{ msg }}</span>
    </div>
  </div>
</template>
