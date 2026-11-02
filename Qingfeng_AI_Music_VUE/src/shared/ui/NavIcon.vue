<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string,string> = {
  home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z',
  create: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',
  music: 'M9 18V5l12-2v13M9 9l12-2M9 18c0 1.7-1.6 3-3.5 3S2 20 2 18.5 3.6 16 5.5 16 9 16.3 9 18Zm12-2c0 1.7-1.6 3-3.5 3S14 18 14 16.5s1.6-2.5 3.5-2.5 3.5.3 3.5 2Z',
  recovery: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4Zm-4 9 3 3 5-6',
  play: 'm9 5 11 7-11 7V5Z',
  guide: 'M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-3H4V4Zm9 3a3 3 0 0 1 3-3h5v14h-4a4 4 0 0 0-4 3',
  menu: 'M4 6h16M4 12h16M4 18h16',
  close: 'm6 6 12 12M18 6 6 18',
  arrow: 'M5 12h14m-5-5 5 5-5 5',
}
</script>
<template><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.music"/></svg></template>
