<script setup lang="ts">
import { ref } from 'vue';
import { desktopEnabled, requestDesktopPermission, setDesktop, setSound, soundEnabled } from '../notify';
import { state, updateSettings } from '../store';

defineEmits<{ close: [] }>();

const timeoutSec = ref(state.settings === null ? '180' : String(state.settings.defaultTimeoutMs / 1000));
const modelName = ref(state.settings?.modelName ?? 'chatshy');
const currentPassword = ref('');
const newPassword = ref('');
const basicMsg = ref('');
const pwdMsg = ref('');
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
    <div class="modal">
      <header class="modal-head">
        <h2>设置</h2>
        <button class="icon-btn" @click="$emit('close')">✕</button>
      </header>

      <section class="modal-body">
        <h3>提醒</h3>
        <label class="check-row"><input v-model="notifyDesktop" type="checkbox" @change="toggleDesktop" /> 桌面通知（需要 HTTPS 与浏览器授权）</label>
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
