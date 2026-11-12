<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ name: string; src?: string }>()
const initial = computed(() => Array.from(props.name.trim())[0]?.toLocaleUpperCase() || '♪')
</script>

<template>
  <span class="artist-avatar" aria-hidden="true">
    <img v-if="src" :src="src" alt="">
    <span v-else>{{ initial }}</span>
  </span>
</template>

<style scoped>
.artist-avatar {
  display: inline-grid;
  flex: none;
  width: var(--artist-avatar-size, 80px);
  aspect-ratio: 1;
  place-items: center;
  overflow: hidden;
  border-radius: 50%;
  background: #f7d6cd;
  color: #6a272e;
  font-size: calc(var(--artist-avatar-size, 80px) * .39);
  font-weight: 650;
}
.artist-avatar img { width: 100%; height: 100%; object-fit: cover; }
</style>
