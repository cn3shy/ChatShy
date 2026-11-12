<script setup lang="ts">
import { computed, ref } from 'vue';
import { removeProvider, saveProvider, state, testProvider } from '../store';
import type { ProviderInfo } from '../types';

const editingId = ref<string | null>(null);
const form = ref({ name: '', baseUrl: '', apiKey: '', model: '' });
const busy = ref(false);
const msg = ref('');
const testResults = ref<Record<string, { ok: boolean; text: string }>>({});

const isEditing = computed(() => editingId.value !== null);

function resultOf(id: string): { ok: boolean; text: string } | null {
  return testResults.value[id] ?? null;
}

function resetForm(): void {
  editingId.value = null;
  form.value = { name: '', baseUrl: '', apiKey: '', model: '' };
}

function startEdit(p: ProviderInfo): void {
  editingId.value = p.id;
  form.value = { name: p.name, baseUrl: p.baseUrl, apiKey: '', model: p.model };
  msg.value = '';
}

async function save(): Promise<void> {
  busy.value = true;
  msg.value = '';
  const res = await saveProvider(
    {
      name: form.value.name.trim(),
      baseUrl: form.value.baseUrl.trim(),
      apiKey: form.value.apiKey,
      model: form.value.model.trim(),
    },
    editingId.value ?? undefined,
  );
  busy.value = false;
  if (res.ok) {
    msg.value = '已保存 ✓';
    resetForm();
  } else {
    msg.value = res.error ?? '保存失败';
  }
}

async function remove(p: ProviderInfo): Promise<void> {
  if (!confirm(`删除厂商「${p.name}」？历史回复不受影响。`)) return;
  const res = await removeProvider(p.id);
  if (!res.ok) {
    msg.value = res.error ?? '删除失败';
    return;
  }
  if (editingId.value === p.id) resetForm();
}

async function test(p: ProviderInfo): Promise<void> {
  testResults.value[p.id] = { ok: true, text: '测试中…' };
  const res = await testProvider(p.id);
  testResults.value[p.id] = res.ok
    ? { ok: true, text: `✓ ${res.latencyMs} ms · ${(res.preview ?? '').slice(0, 40)}` }
    : { ok: false, text: res.error ?? '测试失败' };
}
</script>

<template>
  <section class="provider-section">
    <p v-if="state.providers.length === 0" class="note muted">还没有厂商配置。填好下面的表单即可添加。</p>
    <div v-else class="provider-list">
      <div v-for="p in state.providers" :key="p.id" class="provider-card">
        <div class="provider-main">
          <div class="provider-title">
            <b>{{ p.name }}</b>
            <span class="tag">{{ p.model }}</span>
          </div>
          <div class="provider-url">{{ p.baseUrl }}</div>
          <div class="provider-key">Key：{{ p.apiKeyMasked || '未设置' }}</div>
          <div
            v-if="resultOf(p.id) !== null"
            class="note provider-test"
            :class="resultOf(p.id)?.ok ? 'ok-text' : 'err'"
          >
            {{ resultOf(p.id)?.text }}
          </div>
        </div>
        <div class="provider-actions">
          <button class="ghost-btn small" @click="test(p)">测试</button>
          <button class="ghost-btn small" @click="startEdit(p)">编辑</button>
          <button class="ghost-btn small danger" @click="remove(p)">删除</button>
        </div>
      </div>
    </div>

    <div class="provider-form">
      <h4>{{ isEditing ? '编辑厂商' : '添加厂商' }}</h4>
      <div class="provider-form-grid">
        <label>
          名称
          <input v-model="form.name" placeholder="DeepSeek" />
        </label>
        <label>
          模型名
          <input v-model="form.model" placeholder="deepseek-chat" />
        </label>
        <label class="wide">
          接口地址（Base URL，不含 /chat/completions）
          <input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
        </label>
        <label class="wide">
          API Key
          <input
            v-model="form.apiKey"
            type="password"
            autocomplete="off"
            :placeholder="isEditing ? '留空表示不修改' : 'sk-...'"
          />
        </label>
      </div>
      <div class="provider-form-actions">
        <button class="primary-btn" :disabled="busy" @click="save">
          {{ isEditing ? '保存修改' : '添加' }}
        </button>
        <button v-if="isEditing" class="ghost-btn" @click="resetForm">取消</button>
      </div>
      <p v-if="msg" class="note">{{ msg }}</p>
      <p class="note muted">
        仅支持 OpenAI 兼容接口。示例：DeepSeek
        <code>https://api.deepseek.com/v1</code>（deepseek-chat）；通义千问
        <code>https://dashscope.aliyuncs.com/compatible-mode/v1</code>（qwen-plus）；GLM
        <code>https://open.bigmodel.cn/api/paas/v4</code>（glm-4-flash）。
      </p>
      <p class="note muted">API Key 只存本地数据库，接口只回显末四位；编辑时留空即沿用旧 Key。</p>
    </div>
  </section>
</template>
