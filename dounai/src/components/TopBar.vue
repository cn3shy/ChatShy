<script setup lang="ts">
defineProps<{ model: string }>();
defineEmits<{ menu: [] }>();
</script>

<template>
  <header class="topbar">
    <button class="icon-btn menu-btn" title="菜单" @click="$emit('menu')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M3 6h18M3 12h18M3 18h18" />
      </svg>
    </button>
    <div class="model-chip" title="当前模型">
      <img src="/icon.jpg" alt="" />
      <span>{{ model }}</span>
    </div>
  </header>
</template>
