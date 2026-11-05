<template>
  <div class="studio-atmosphere" aria-hidden="true">
    <svg class="studio-wave studio-wave-left" viewBox="0 0 480 180" fill="none">
      <path d="M-30 103C80-32 168 12 243 90s158 95 274 4" stroke="currentColor" stroke-width="40" opacity=".06"/>
      <path v-for="offset in [0, 5, 10, 15, 20, 25, 30]" :key="offset" :transform="`translate(0 ${offset})`" d="M-30 55C80-35 168 12 243 90s158 95 274 4" stroke="currentColor" stroke-width=".6" opacity=".2"/>
    </svg>
    <svg class="studio-wave studio-wave-right" viewBox="0 0 480 180" fill="none">
      <path d="M-30 103C80-32 168 12 243 90s158 95 274 4" stroke="currentColor" stroke-width="40" opacity=".06"/>
      <path v-for="offset in [0, 5, 10, 15, 20, 25, 30]" :key="offset" :transform="`translate(0 ${offset})`" d="M-30 55C80-35 168 12 243 90s158 95 274 4" stroke="currentColor" stroke-width=".6" opacity=".2"/>
    </svg>
    <div class="studio-brand-orb"><span></span><i></i></div>
  </div>
</template>
