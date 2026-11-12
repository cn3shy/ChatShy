<script setup lang="ts">
const emit = defineEmits<{ fill: [text: string] }>();

const chips = [
  { emoji: '📝', label: '写一份周报', fill: '帮我写一份本周工作周报,内容包括完成事项、遇到的问题和下周计划' },
  { emoji: '💻', label: '解释一段代码', fill: '解释一下这段代码的作用:' },
  { emoji: '✈️', label: '做个旅行计划', fill: '帮我规划一个周末两天的短途旅行,轻松一点,预算 1000 元以内' },
  { emoji: '🍂', label: '写首小诗', fill: '以「秋天的傍晚」为题写一首小诗' },
];
</script>

<template>
  <section class="welcome">
    <img class="welcome-avatar" src="/icon.jpg" alt="豆奶" />
    <h1>你好呀,我是豆奶</h1>
    <p class="welcome-sub">有什么想聊的,都可以问我</p>
    <div class="chips">
      <button v-for="chip in chips" :key="chip.label" class="chip" @click="emit('fill', chip.fill)">
        <span class="chip-emoji">{{ chip.emoji }}</span>{{ chip.label }}
      </button>
    </div>
  </section>
</template>
