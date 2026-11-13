<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string,string> = {
  home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z',
  create: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z',
  music: 'M9 18V5l12-2v13M9 9l12-2M9 18c0 1.7-1.6 3-3.5 3S2 20 2 18.5 3.6 16 5.5 16 9 16.3 9 18Zm12-2c0 1.7-1.6 3-3.5 3S14 18 14 16.5s1.6-2.5 3.5-2.5 3.5.3 3.5 2Z',
  recovery: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4Zm-4 9 3 3 5-6',
  play: 'm9 5 11 7-11 7V5Z',
  search: 'M20 20l-4.4-4.4M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
  guide: 'M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-3H4V4Zm9 3a3 3 0 0 1 3-3h5v14h-4a4 4 0 0 0-4 3',
  menu: 'M4 6h16M4 12h16M4 18h16',
  close: 'm6 6 12 12M18 6 6 18',
  account: 'M20 21v-2a8 8 0 0 0-16 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  logout: 'M9 4H4v16h5m5-12 4 4-4 4m-5-4h12',
  arrow: 'M5 12h14m-5-5 5 5-5 5',
  upload: 'M12 16V3m-5 5 5-5 5 5M3 15v4a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4',
  landscape: 'm3 19 6-8 4 5 3-4 5 7M14 7a2 2 0 1 0 4 0 2 2 0 0 0-4 0Z',
  people: 'M2 20v-2a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v2m6 0v-2a4 4 0 0 0-3-3.9M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm7-7a3.5 3.5 0 0 1 0 7',
  cup: 'M3 20h17M5 5h12v7a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4V5Zm12 1h2a2 2 0 0 1 0 4h-2',
  agent: 'M6 8h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Zm6-4v4M9 13h.01M15 13h.01M9 16h6',
}
</script>
<template><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.music"/></svg></template>
