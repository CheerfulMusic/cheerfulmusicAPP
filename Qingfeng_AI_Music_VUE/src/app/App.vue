<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { platform } from '../platform'
import WebLayout from './layouts/PremiumWebLayout.vue'
import AppLayout from './layouts/AppLayout.vue'
import LoginModal from '../modules/identity/components/LoginModal.vue'
import BetaAccessGate from '../modules/beta-access/BetaAccessGate.vue'
const layout = computed(() => platform.kind === 'web' ? WebLayout : AppLayout)
const route = useRoute()
const standalonePreview = computed(() => import.meta.env.DEV && route.name === 'payment-preview')
</script>
<template><RouterView v-if="standalonePreview" /><BetaAccessGate v-else :public-access="route.name === 'gift'"><component :is="layout"><RouterView /></component><LoginModal /></BetaAccessGate></template>
