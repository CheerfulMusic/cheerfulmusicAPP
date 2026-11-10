<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  label: string
  value: string
  hint?: string
  icon?: 'eye' | 'users' | 'clock' | 'hourglass'
  tint?: 'coral' | 'green' | 'violet' | 'amber'
  spark?: number[]
}>()

const PALETTE = {
  coral: { bg: '#fdeeeb', fg: '#b93b2c', value: '#b93b2c', line: '#d4483b' },
  green: { bg: '#edf6f0', fg: '#2f7d5c', value: '#27724f', line: '#2f7d5c' },
  violet: { bg: '#f1f0fb', fg: '#5b55a8', value: '#231f1d', line: '#5b55a8' },
  amber: { bg: '#fdf4e5', fg: '#a9741f', value: '#231f1d', line: '#c08a2e' },
}
const palette = computed(() => PALETTE[props.tint ?? 'coral'])

/** 折线只看形状，统一做归一化；两个点以上才画。 */
const spark = computed(() => {
  const points = props.spark ?? []
  if (points.length < 2) return null
  const width = 148, height = 34, pad = 2
  const max = Math.max(1, ...points)
  const min = Math.min(...points)
  const span = Math.max(1, max - min)
  const x = (i: number) => pad + (i * (width - pad * 2)) / (points.length - 1)
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2)
  return { line: points.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' '), width, height }
})
</script>

<template>
  <section class="kpi" :style="{ background: palette.bg }">
    <header class="kpi-head">
      <span class="kpi-label">{{ label }}</span>
      <span v-if="icon" class="kpi-icon" :style="{ color: palette.fg }" aria-hidden="true">
        <svg v-if="icon === 'eye'" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M1.8 10S4.9 4.8 10 4.8 18.2 10 18.2 10 15.1 15.2 10 15.2 1.8 10 1.8 10Z"/><circle cx="10" cy="10" r="2.6"/></svg>
        <svg v-else-if="icon === 'users'" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="7.6" cy="7.4" r="2.8"/><path d="M2.4 16.2c.5-2.8 2.7-4.3 5.2-4.3s4.7 1.5 5.2 4.3"/><path d="M13.4 5.2a2.6 2.6 0 0 1 0 5"/><path d="M14.6 11.9c2 .4 3.2 1.8 3.4 3.8"/></svg>
        <svg v-else-if="icon === 'clock'" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="10" cy="10" r="7.4"/><path d="M10 6v4.3l2.8 1.7"/></svg>
        <svg v-else viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M4.4 3h11.2M4.4 17h11.2"/><path d="M5.6 3c0 3.6 4.4 4.6 4.4 7s-4.4 3.4-4.4 7"/><path d="M14.4 3c0 3.6-4.4 4.6-4.4 7s4.4 3.4 4.4 7"/></svg>
      </span>
    </header>
    <strong class="kpi-value" :style="{ color: palette.value }">{{ value }}</strong>
    <p v-if="hint" class="kpi-hint">{{ hint }}</p>
    <svg v-if="spark" class="kpi-spark" :viewBox="`0 0 ${spark.width} ${spark.height}`" preserveAspectRatio="none" aria-hidden="true">
      <path :d="spark.line" fill="none" :stroke="palette.line" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" opacity="0.7" />
    </svg>
  </section>
</template>

<style scoped>
.kpi {
  display: flex; flex-direction: column; gap: 5px;
  min-height: 138px; padding: 18px 20px 16px;
  border-radius: 14px;
}
.kpi-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.kpi-label { color: #5f534e; font-size: 12.5px; }
.kpi-icon { display: grid; place-items: center; width: 26px; height: 26px; opacity: .9; }
.kpi-icon svg { width: 17px; height: 17px; }
.kpi-value { margin-top: 4px; font-size: 29px; line-height: 1.15; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.kpi-hint { margin: 0; color: #7d6f69; font-size: 11.5px; line-height: 1.6; }
.kpi-spark { width: 100%; height: 34px; margin-top: auto; }
@media (max-width: 720px) {
  .kpi { min-height: 124px; padding: 15px 16px; }
  .kpi-value { font-size: 25px; }
}
</style>
