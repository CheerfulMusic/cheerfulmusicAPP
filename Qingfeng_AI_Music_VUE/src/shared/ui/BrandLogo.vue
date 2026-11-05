<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; standalone?: boolean }>(), { compact: false, standalone: true })
</script>
<template><span class="brand-logo" :class="{ 'brand-logo-compact': compact }"><img :src="standalone ? '/brand/logo-symbol.svg' : '/brand/logo-mark.svg'" alt="" width="48" height="48"><span class="brand-logo-type"><strong>Cheerful Music</strong></span></span></template>
