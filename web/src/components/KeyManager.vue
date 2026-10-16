<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api } from '../api';
import type { KeyInfo } from '../types';

defineEmits<{ close: [] }>();

const keys = ref<KeyInfo[]>([]);
const error = ref('');
const creating = ref(false);
const plaintext = ref<string | null>(null);
const copied = ref(false);

const newName = ref('');
const newTimeoutSec = ref('');
const newDaily = ref('');
const newMaxPending = ref('');

async function load(): Promise<void> {
  try {
    keys.value = (await api.keys()).keys;
  } catch {
    error.value = '加载失败';
  }
}

onMounted(load);

async function create(): Promise<void> {
  if (creating.value) return;
  creating.value = true;
  error.value = '';
  try {
    const res = await api.createKey({
      name: newName.value.trim() === '' ? undefined : newName.value.trim(),
      timeoutMs: newTimeoutSec.value.trim() === '' ? undefined : Math.round(Number(newTimeoutSec.value) * 1000),
      dailyLimit: newDaily.value.trim() === '' ? undefined : Number(newDaily.value),
      maxPending: newMaxPending.value.trim() === '' ? undefined : Number(newMaxPending.value),
    });
    plaintext.value = res.plaintext;
    copied.value = false;
    newName.value = '';
    newTimeoutSec.value = '';
    newDaily.value = '';
    newMaxPending.value = '';
    await load();
  } catch {
    error.value = '创建失败：检查数值输入（超时秒数/限额需为正数）';
  }
  creating.value = false;
}

async function toggleEnabled(k: KeyInfo): Promise<void> {
  await api.updateKey(k.id, { enabled: !k.enabled });
  await load();
}

async function remove(k: KeyInfo): Promise<void> {
  if (!confirm(`删除 key「${k.name}」？使用该 key 的调用方将立即 401。`)) return;
  await api.deleteKey(k.id);
  await load();
}

async function copyPlaintext(): Promise<void> {
  if (plaintext.value === null) return;
  try {
    await navigator.clipboard.writeText(plaintext.value);
    copied.value = true;
  } catch {
    /* 手动复制 */
  }
}

function fmtTimeout(ms: number | null): string {
  return ms === null ? '默认' : `${Math.round(ms / 1000)}s`;
}

function fmtLastUsed(ts: number | null): string {
  return ts === null ? '从未' : new Date(ts).toLocaleString();
}
</script>

<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal wide">
      <header class="modal-head">
        <h2>API Key 管理</h2>
        <button class="icon-btn" @click="$emit('close')">✕</button>
      </header>

      <section class="modal-body">
        <div v-if="plaintext !== null" class="plaintext-box">
          <p><b>新 key 已生成——明文只显示这一次，请立即复制保存：</b></p>
          <div class="plaintext-row">
            <code>{{ plaintext }}</code>
            <button class="ghost-btn" @click="copyPlaintext">{{ copied ? '已复制 ✓' : '复制' }}</button>
            <button class="ghost-btn" @click="plaintext = null">关闭</button>
          </div>
        </div>

        <p v-if="error" class="err">{{ error }}</p>

        <table class="key-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>前缀</th>
              <th>状态</th>
              <th>超时</th>
              <th>日限额</th>
              <th>挂起上限</th>
              <th>最近使用</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="k in keys" :key="k.id" :class="{ disabled: !k.enabled }">
              <td>{{ k.name }}</td>
              <td><code>{{ k.keyPrefix }}</code></td>
              <td>
                <button class="ghost-btn small" @click="toggleEnabled(k)">
                  {{ k.enabled ? '启用中' : '已禁用' }}
                </button>
              </td>
              <td>{{ fmtTimeout(k.timeoutMs) }}</td>
              <td>{{ k.dailyLimit ?? '不限' }}</td>
              <td>{{ k.maxPending }}</td>
              <td class="muted">{{ fmtLastUsed(k.lastUsedAt) }}</td>
              <td><button class="ghost-btn small danger" @click="remove(k)">删除</button></td>
            </tr>
            <tr v-if="keys.length === 0">
              <td colspan="8" class="muted">还没有 key，先给调用方生成一个吧</td>
            </tr>
          </tbody>
        </table>

        <h3>生成新 key</h3>
        <div class="key-form">
          <label>名称 <input v-model="newName" placeholder="如：小明" /></label>
          <label>超时(秒) <input v-model="newTimeoutSec" placeholder="默认" inputmode="numeric" /></label>
          <label>日限额 <input v-model="newDaily" placeholder="不限" inputmode="numeric" /></label>
          <label>挂起上限 <input v-model="newMaxPending" placeholder="5" inputmode="numeric" /></label>
          <button class="primary-btn" :disabled="creating" @click="create">生成</button>
        </div>
      </section>
    </div>
  </div>
</template>
