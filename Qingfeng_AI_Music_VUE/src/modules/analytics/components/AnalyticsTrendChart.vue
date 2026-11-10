<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrendPoint } from '../model/dwell'

const props = defineProps<{ points: TrendPoint[] }>()

const W = 880, H = 300, PAD = { l: 46, r: 18, t: 18, b: 34 }
const hovered = ref(-1)

const hasData = computed(() => props.points.some(point => point.pageviews > 0 || point.uv > 0))

/** 取一个「好看」的纵轴上限，避免出现 137 这种刻度。 */
const maxValue = computed(() => {
  const peak = Math.max(1, ...props.points.map(point => Math.max(point.pageviews, point.uv)))
  const magnitude = 10 ** Math.floor(Math.log10(peak))
  const normalized = peak / magnitude
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * magnitude
})

const x = (index: number) => PAD.l + (index * (W - PAD.l - PAD.r)) / Math.max(1, props.points.length - 1)
const y = (value: number) => H - PAD.b - (value / maxValue.value) * (H - PAD.t - PAD.b)

const pvPath = computed(() => props.points.map((point, index) => `${index ? 'L' : 'M'}${x(index).toFixed(1)},${y(point.pageviews).toFixed(1)}`).join(' '))
const uvPath = computed(() => props.points.map((point, index) => `${index ? 'L' : 'M'}${x(index).toFixed(1)},${y(point.uv).toFixed(1)}`).join(' '))
/** 点太多时不画圆点，避免 90 天窗口糊成一排。 */
const showDots = computed(() => props.points.length > 0 && props.points.length <= 45)

const yTicks = computed(() => [0, 0.25, 0.5, 0.75, 1].map(ratio => ({ value: Math.round(maxValue.value * ratio), y: y(maxValue.value * ratio) })))

const xTicks = computed(() => {
  const total = props.points.length
  if (!total) return []
  const wanted = total <= 7 ? total : 6
  const step = Math.max(1, Math.ceil((total - 1) / (wanted - 1)))
  const result: { index: number; label: string }[] = []
  for (let index = 0; index < total; index += step) result.push({ index, label: shortDay(props.points[index]!.d) })
  if (result[result.length - 1]!.index !== total - 1) result.push({ index: total - 1, label: shortDay(props.points[total - 1]!.d) })
  return result
})

function shortDay(day: string): string {
  const [, month, date] = day.split('-')
  return `${Number(month)}/${Number(date)}`
}

function locate(event: PointerEvent | MouseEvent): number {
  const box = (event.currentTarget as SVGSVGElement).getBoundingClientRect()
  if (!box.width || props.points.length < 2) return -1
  const ratio = ((event.clientX - box.left) / box.width) * W
  const index = Math.round(((ratio - PAD.l) / (W - PAD.l - PAD.r)) * (props.points.length - 1))
  return Math.min(props.points.length - 1, Math.max(0, index))
}

function onMove(event: PointerEvent) { hovered.value = locate(event) }
function onLeave() { hovered.value = -1 }

const active = computed(() => (hovered.value >= 0 ? props.points[hovered.value] : undefined))
const activeLeft = computed(() => (hovered.value >= 0 ? (x(hovered.value) / W) * 100 : 0))
</script>

<template>
  <div class="trend">
    <div v-if="!hasData" class="trend-empty">
      <strong>这段时间还没有访问</strong>
      <span>有真实浏览后，PV / UV 曲线会出现在这里。</span>
    </div>
    <div v-else class="trend-canvas">
      <svg class="trend-svg" :viewBox="`0 0 ${W} ${H}`" role="img"
           :aria-label="`访问趋势，共 ${points.length} 天，最高 PV ${Math.max(...points.map(p => p.pageviews))}`"
           @pointermove="onMove" @pointerleave="onLeave">
        <g class="grid">
          <g v-for="tick in yTicks" :key="tick.value">
            <line :x1="PAD.l" :x2="W - PAD.r" :y1="tick.y" :y2="tick.y" />
            <text :x="PAD.l - 10" :y="tick.y + 4" text-anchor="end">{{ tick.value }}</text>
          </g>
        </g>
        <g class="axis">
          <text v-for="tick in xTicks" :key="tick.index" :x="x(tick.index)" :y="H - 12" text-anchor="middle">{{ tick.label }}</text>
        </g>
        <path :d="uvPath" class="line uv" />
        <path :d="pvPath" class="line pv" />
        <g v-if="showDots">
          <circle v-for="(point, index) in points" :key="`pv-${index}`" :cx="x(index)" :cy="y(point.pageviews)" r="2.6" class="point pv" />
          <circle v-for="(point, index) in points" :key="`uv-${index}`" :cx="x(index)" :cy="y(point.uv)" r="2.4" class="point uv" />
        </g>
        <g v-if="active" class="focus">
          <line :x1="x(hovered)" :x2="x(hovered)" :y1="PAD.t" :y2="H - PAD.b" />
          <circle :cx="x(hovered)" :cy="y(active.pageviews)" r="4.5" class="dot pv" />
          <circle :cx="x(hovered)" :cy="y(active.uv)" r="4.5" class="dot uv" />
        </g>
        <rect :x="PAD.l" :y="PAD.t" :width="W - PAD.l - PAD.r" :height="H - PAD.t - PAD.b" fill="transparent" />
      </svg>
      <div class="trend-legend">
        <span><i class="swatch pv"></i>PV 访问量</span>
        <span><i class="swatch uv"></i>UV 独立访客</span>
      </div>
      <div v-if="active" class="trend-tip" :style="{ left: `${activeLeft}%` }">
        <strong>{{ active.d }}</strong>
        <span><i class="swatch pv"></i>PV {{ active.pageviews.toLocaleString('zh-CN') }}</span>
        <span><i class="swatch uv"></i>UV {{ active.uv.toLocaleString('zh-CN') }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trend { position: relative; }
.trend-canvas { position: relative; }
.trend-svg { display: block; width: 100%; height: auto; touch-action: pan-y; }
.grid line { stroke: #f2e8e4; stroke-width: 1; }
.grid text, .axis text { fill: #a2948e; font-size: 10.5px; }
.line { fill: none; stroke-linecap: round; stroke-linejoin: round; }
.line.pv { stroke: #d4483b; stroke-width: 2.4; }
.line.uv { stroke: #2f7d8c; stroke-width: 2; }
.focus line { stroke: #d8c8c2; stroke-width: 1; stroke-dasharray: 4 4; }
.point { stroke: #fff; stroke-width: 1.4; }
.point.pv { fill: #d4483b; }
.point.uv { fill: #2f7d8c; }
.dot.pv { fill: #d4483b; stroke: #fff; stroke-width: 2; }
.dot.uv { fill: #2f7d8c; stroke: #fff; stroke-width: 2; }
.trend-legend { display: flex; justify-content: center; gap: 22px; margin-top: 4px; color: #7d6f69; font-size: 11.5px; }
.trend-legend span { display: inline-flex; align-items: center; gap: 6px; }
.trend-tip {
  position: absolute; top: 6px; z-index: 2; display: grid; gap: 3px; min-width: 116px;
  padding: 9px 11px; border-radius: 10px; background: #fff; border: 1px solid #f0e1dc;
  box-shadow: 0 8px 22px rgba(88, 44, 36, .12); transform: translateX(-50%); pointer-events: none;
}
.trend-tip strong { font-size: 11px; color: #6f625c; font-weight: 500; }
.trend-tip span { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #2c2522; font-variant-numeric: tabular-nums; }
.swatch { width: 8px; height: 8px; border-radius: 50%; }
.swatch.pv { background: #d4483b; }
.swatch.uv { background: #2f7d8c; }
.trend-empty { display: grid; gap: 6px; place-content: center; min-height: 240px; text-align: center; }
.trend-empty strong { font-size: 14px; color: #2c2522; font-weight: 550; }
.trend-empty span { font-size: 12px; color: #9a8b84; }
@media (max-width: 720px) {
  .trend-tip { min-width: 100px; padding: 7px 9px; }
}
</style>
