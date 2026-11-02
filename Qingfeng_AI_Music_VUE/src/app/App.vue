<script setup lang="ts">
import { computed } from 'vue'
import { platform } from '../platform'
import WebLayout from './layouts/PremiumWebLayout.vue'
import AppLayout from './layouts/AppLayout.vue'
const layout = computed(() => platform.kind === 'web' ? WebLayout : AppLayout)
</script>
<template><component :is="layout"><RouterView /></component></template>
