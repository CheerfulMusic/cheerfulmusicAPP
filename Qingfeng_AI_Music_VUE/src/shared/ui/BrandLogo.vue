<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; standalone?: boolean }>(), { compact: false, standalone: true })
</script>
<template><span class="brand-logo" :class="{ 'brand-logo-compact': compact }"><img :src="standalone ? '/brand/logo-symbol.svg' : '/brand/logo-mark.svg'" alt="" width="48" height="48"><span class="brand-logo-type"><img class="brand-wordmark" src="/brand/wordmark-en.svg" alt="CHEERFUL MUSIC" lang="en" height="40"></span></span></template>

<style scoped>
.brand-logo { gap:10px; margin:0; }
.brand-logo-type { margin:0; }
.brand-wordmark { display:block; width:auto; height:40px; max-width:none; }
.brand-logo-compact .brand-wordmark { height:30px; }
@media(max-width:700px) {
  .brand-wordmark,.brand-logo-compact .brand-wordmark { height:27px; }
}
@media(max-width:380px) {
  .brand-wordmark,.brand-logo-compact .brand-wordmark { height:24px; }
}
</style>
