<script setup lang="ts">
import { ref } from 'vue';
import { desktopEnabled, requestDesktopPermission, setDesktop, setSound, soundEnabled } from '../notify';
import { regenerateKey, state, updateSettings } from '../store';
import ProviderSettings from './ProviderSettings.vue';

defineEmits<{ close: [] }>();

const timeoutSec = ref(state.settings === null ? '180' : String(state.settings.defaultTimeoutMs / 1000));
const modelName = ref(state.settings?.modelName ?? 'chatshy');
const currentPassword = ref('');
const newPassword = ref('');
const basicMsg = ref('');
const pwdMsg = ref('');
const keyMsg = ref('');
const keyCopied = ref(false);
const configMsg = ref('');
const configCopied = ref(false);
const busy = ref(false);

const notifyDesktop = ref(desktopEnabled());
const notifySound = ref(soundEnabled());

async function toggleDesktop(): Promise<void> {
  if (notifyDesktop.value) await requestDesktopPermission();
  setDesktop(notifyDesktop.value);
}

function toggleSoundPref(): void {
  setSound(notifySound.value);
}

async function copyKey(): Promise<void> {
  const key = state.settings?.callerKey ?? '';
  if (key === '') return;
  try {
    await navigator.clipboard.writeText(key);
  } catch {
    // 非 HTTPS 上下文里 clipboard API 不可用，提示手动复制
    keyMsg.value = '复制失败，请手动选中上面的 Key 复制';
    return;
  }
  keyMsg.value = '';
  keyCopied.value = true;
  setTimeout(() => (keyCopied.value = false), 1500);
}

/**
 * 复制给调用方的接入配置：Base URL / API Key / 模型名三行。
 * 域名取自当前页面地址——管理员是在自己部署的域名下打开操作台的，这个值就是对的。
 */
function configText(): string {
  const key = state.settings?.callerKey ?? '';
  const model = state.settings?.modelName ?? 'chatshy';
  return `Base URL: ${window.location.origin}/v1\nAPI Key: ${key}\nModel: ${model}`;
}

async function copyConfig(): Promise<void> {
  if ((state.settings?.callerKey ?? '') === '') return;
  try {
    await navigator.clipboard.writeText(configText());
  } catch {
    configMsg.value = '复制失败，请手动选中上面的 Key 复制';
    return;
  }
  configMsg.value = '';
  configCopied.value = true;
  setTimeout(() => (configCopied.value = false), 1500);
}

async function resetKey(): Promise<void> {
  if (!confirm('重新生成后旧 Key 立即失效，所有调用方都要改配置。确定继续？')) return;
  busy.value = true;
  try {
    await regenerateKey();
    keyMsg.value = '已重新生成 ✓ 记得通知所有调用方更新配置';
  } catch {
    keyMsg.value = '重新生成失败';
  }
  busy.value = false;
}

async function saveBasic(): Promise<void> {
  busy.value = true;
  basicMsg.value = '';
  const timeoutMs = Math.round(Number(timeoutSec.value) * 1000);
  const res = await updateSettings({ defaultTimeoutMs: timeoutMs, modelName: modelName.value.trim() });
  basicMsg.value = res.ok ? '已保存 ✓' : (res.error ?? '保存失败');
  busy.value = false;
}

async function savePassword(): Promise<void> {
  busy.value = true;
  pwdMsg.value = '';
  const res = await updateSettings({
    adminPassword: newPassword.value,
    currentPassword: currentPassword.value,
  });
  pwdMsg.value = res.ok ? '密码已更新 ✓' : (res.error ?? '保存失败');
  if (res.ok) {
    currentPassword.value = '';
    newPassword.value = '';
  }
  busy.value = false;
}
</script>

<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal wide">
      <header class="modal-head">
        <h2>设置</h2>
        <button class="icon-btn" @click="$emit('close')">✕</button>
      </header>

      <section class="modal-body">
        <h3>调用方 Key</h3>
        <div class="key-row">
          <code class="key-value">{{ state.settings?.callerKey || '（尚未生成）' }}</code>
          <button class="ghost-btn small" :disabled="!state.settings?.callerKey" @click="copyKey">
            {{ keyCopied ? '已复制 ✓' : '复制' }}
          </button>
          <button class="ghost-btn small danger" :disabled="busy" @click="resetKey">重新生成</button>
        </div>
        <p class="note muted">
          全项目只有这一个 Key，所有调用方共用。重新生成后旧 Key <b>立即失效</b>。
        </p>
        <div class="share-row">
          <button class="ghost-btn small" :disabled="!state.settings?.callerKey" @click="copyConfig">
            {{ configCopied ? '已复制 ✓' : '复制接入配置' }}
          </button>
          <span class="note muted">Base URL + API Key + 模型名三行，直接发给调用方</span>
        </div>
        <p v-if="configMsg" class="note">{{ configMsg }}</p>
        <p v-if="keyMsg" class="note">{{ keyMsg }}</p>

        <h3>AI 厂商转发</h3>
        <ProviderSettings />

        <h3>提醒</h3>
        <label class="check-row"
          ><input v-model="notifyDesktop" type="checkbox" @change="toggleDesktop" /> 桌面通知（需要 HTTPS
          与浏览器授权）</label
        >
        <label class="check-row"><input v-model="notifySound" type="checkbox" @change="toggleSoundPref" /> 新请求提示音</label>

        <h3>服务默认值</h3>
        <div class="key-form">
          <label>默认挂起超时(秒) <input v-model="timeoutSec" inputmode="numeric" /></label>
          <label>模型名 <input v-model="modelName" /></label>
          <button class="primary-btn" :disabled="busy" @click="saveBasic">保存</button>
        </div>
        <p v-if="basicMsg" class="note">{{ basicMsg }}</p>
        <p class="note muted">模型名用于 GET /v1/models 展示；调用方请求里的 model 一律原样回显。</p>

        <h3>修改管理员密码</h3>
        <div class="key-form">
          <label>当前密码 <input v-model="currentPassword" type="password" autocomplete="current-password" /></label>
          <label>新密码 <input v-model="newPassword" type="password" autocomplete="new-password" /></label>
          <button class="primary-btn" :disabled="busy || newPassword === ''" @click="savePassword">更新</button>
        </div>
        <p v-if="pwdMsg" class="note">{{ pwdMsg }}</p>
      </section>
    </div>
  </div>
</template>
