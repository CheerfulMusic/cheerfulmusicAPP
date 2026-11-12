<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { platform } from '../platform'
import WebLayout from './layouts/PremiumWebLayout.vue'
import AppLayout from './layouts/AppLayout.vue'
import WelcomeModal from '../modules/identity/components/WelcomeModal.vue'
import LoginModal from '../modules/identity/components/LoginModal.vue'
import BetaAccessGate from '../modules/beta-access/BetaAccessGate.vue'
import QuickCompletionNotice from '../modules/studio/components/QuickCompletionNotice.vue'
import AnalyticsConsentBanner from '../modules/analytics/components/AnalyticsConsentBanner.vue'
const route = useRoute()
const layout = computed(() => platform.kind === 'web' || route.name === 'artist-home' ? WebLayout : AppLayout)
const publicListening = computed(() => route.name === 'gift' || route.name === 'artist-home')
const standalonePreview = computed(() => import.meta.env.DEV && route.name === 'payment-preview')
</script>
<template><RouterView v-if="standalonePreview" /><BetaAccessGate v-else :public-access="publicListening"><component :is="layout"><RouterView /></component><template v-if="route.name !== 'artist-home'"><LoginModal /><WelcomeModal /><QuickCompletionNotice /></template></BetaAccessGate><AnalyticsConsentBanner /></template>
