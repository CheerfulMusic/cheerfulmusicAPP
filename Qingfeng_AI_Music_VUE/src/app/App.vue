<script setup lang="ts">
import BrowserDownloadHelp from '../shared/BrowserDownloadHelp.vue'
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
import SeoLanding from './layouts/SeoLanding.vue'
import { t } from '../shared/i18n'
const route = useRoute()
const community = computed(() => route.name === 'artist-home' || route.name === 'discover')
const layout = computed(() => platform.kind === 'web' || community.value ? WebLayout : AppLayout)
const publicListening = computed(() => route.name === 'gift' || community.value)
const standalonePreview = computed(() => import.meta.env.DEV && route.name === 'payment-preview')
const publicPreview = computed(() => platform.kind === 'web' && (route.name === 'home' || route.name === 'help'))
</script>
<template>
  <RouterView v-if="standalonePreview" />
  <BetaAccessGate v-else :public-access="publicListening" :public-preview="publicPreview">
    <template #public-preview="{ openInvite }">
      <SeoLanding :path="route.path">
        <button type="button" class="button" @click="openInvite">{{ t('请输入邀请码') }}</button>
      </SeoLanding>
    </template>
    <component :is="layout"><RouterView /></component>
    <template v-if="!community"><LoginModal /><WelcomeModal /><QuickCompletionNotice /></template>
  </BetaAccessGate>
  <AnalyticsConsentBanner />
  <BrowserDownloadHelp />
</template>
