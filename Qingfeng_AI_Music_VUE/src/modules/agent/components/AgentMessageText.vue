<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ text: string }>()
// Render a small, safe formatting subset; model text never becomes raw HTML.
const lines = computed(() => props.text.split('\n').map(line => ({
  quote: /^>\s?/.test(line),
  parts: line.replace(/^>\s?/, '').split(/(\*\*[^*\n]+\*\*)/g).map(part => ({
    bold: part.startsWith('**') && part.endsWith('**') && part.length > 4,
    text: part.startsWith('**') && part.endsWith('**') && part.length > 4 ? part.slice(2, -2) : part,
  })),
})))
</script>
<template><div class="agent-message-text"><div v-for="(line, i) in lines" :key="i" :class="{ quoted: line.quote }"><template v-for="(part, j) in line.parts" :key="j"><strong v-if="part.bold">{{ part.text }}</strong><span v-else>{{ part.text }}</span></template><br v-if="!line.parts.some(part => part.text)"/></div></div></template>
<style scoped>.agent-message-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.quoted{border-left:3px solid #eaa68a;padding-left:12px;margin:8px 0}</style>
