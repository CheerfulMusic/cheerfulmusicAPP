<script setup lang="ts">
import { t } from '../i18n/index'
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
</script>
<template><span class="brand-logo" :class="{ 'brand-logo-compact': compact }"><img src="/brand/logo-mark.svg" alt="" width="48" height="48"><span class="brand-logo-type"><strong>Cheerful Music</strong><small>{{ t('青风音乐') }}</small></span></span></template>
