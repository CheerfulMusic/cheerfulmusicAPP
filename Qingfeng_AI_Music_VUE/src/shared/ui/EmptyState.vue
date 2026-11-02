<script setup lang="ts">defineProps<{ title: string; description: string }>()</script>
<template><section class="module-card"><h2>{{ title }}</h2><p>{{ description }}</p><slot /></section></template>
