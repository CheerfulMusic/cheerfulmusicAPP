<script setup lang="ts">
defineProps<{playing?:boolean}>()
</script>
<template><svg class="playback-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path v-if="!playing" d="M8 4.8a1 1 0 0 1 1.5-.86l11 7.2a1 1 0 0 1 0 1.72l-11 7.2A1 1 0 0 1 8 19.2Z"/><path v-else d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg></template>
