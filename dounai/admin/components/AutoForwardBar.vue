<script setup lang="ts">
import { computed, ref } from 'vue';
import { setAutoForward, state } from '../store';

/** AI 自动转发开关：服务端持久化，操作台关掉也生效。挂在会话管理页侧栏顶部。 */
const autoBusy = ref(false);
const autoMsg = ref('');
const hasProviders = computed(() => state.providers.length > 0);
const autoEnabled = computed(() => state.settings?.autoForwardEnabled ?? false);
const selectedProviderId = computed(() => state.settings?.autoForwardProviderId ?? '');

async function toggleAuto(enabled: boolean): Promise<void> {
  if (enabled && !hasProviders.value) {
    autoMsg.value = '请先在设置里添加 AI 厂商';
    return;
  }
  let providerId = selectedProviderId.value;
  if (enabled && (providerId === '' || !state.providers.some((p) => p.id === providerId))) {
    providerId = state.providers[0]?.id ?? '';
  }
  autoBusy.value = true;
  autoMsg.value = '';
  const res = await setAutoForward(enabled, providerId);
  autoBusy.value = false;
  if (!res.ok) autoMsg.value = res.error ?? '保存失败';
}

function onToggle(e: Event): void {
  void toggleAuto((e.target as HTMLInputElement).checked);
}

async function changeProvider(providerId: string): Promise<void> {
  if (providerId === '') return;
  autoBusy.value = true;
  autoMsg.value = '';
  // 即使开关关着也记住选择，下次开启直接生效
  const res = await setAutoForward(autoEnabled.value, providerId);
  autoBusy.value = false;
  if (!res.ok) autoMsg.value = res.error ?? '保存失败';
}

function onChangeProvider(e: Event): void {
  void changeProvider((e.target as HTMLSelectElement).value);
}
</script>

<template>
  <div class="auto-forward-bar">
    <label
      class="auto-forward-toggle"
      :class="{ on: autoEnabled }"
      :title="hasProviders
        ? '开启后服务端会自动用所选 AI 回复新请求，操作台关掉也生效'
        : '请先在设置里添加 AI 厂商'"
    >
      <input
        type="checkbox"
        :checked="autoEnabled"
        :disabled="autoBusy || !hasProviders"
        @change="onToggle"
      />
      <span>AI 自动转发</span>
    </label>
    <select
      class="auto-forward-provider"
      :value="selectedProviderId"
      :disabled="autoBusy || !hasProviders"
      @change="onChangeProvider"
    >
      <option v-if="selectedProviderId === ''" value="">选择厂商</option>
      <option v-for="p in state.providers" :key="p.id" :value="p.id">{{ p.name }}</option>
    </select>
  </div>
</template>
