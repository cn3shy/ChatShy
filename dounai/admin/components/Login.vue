<script setup lang="ts">
import { ref } from 'vue';
import { login, state } from '../store';

const password = ref('');
const submitting = ref(false);

async function submit(): Promise<void> {
  if (password.value === '' || submitting.value) return;
  submitting.value = true;
  await login(password.value);
  submitting.value = false;
  if (!state.authed) password.value = '';
}
</script>

<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="submit">
      <h1><span class="logo-dot"></span>ChatShy</h1>
      <p class="sub">人肉大模型 · 操作台</p>
      <input v-model="password" type="password" placeholder="管理员密码" autofocus autocomplete="current-password" />
      <p v-if="state.loginError" class="err">{{ state.loginError }}</p>
      <button type="submit" :disabled="submitting || password === ''">进入</button>
    </form>
  </div>
</template>
