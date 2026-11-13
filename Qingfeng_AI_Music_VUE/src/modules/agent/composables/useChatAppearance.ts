import { computed, ref, watch } from 'vue'
export const appearanceKey = 'qingfeng.agent.appearance.v1'
export const fonts = {
  modern: '"Inter", "PingFang SC", "Microsoft YaHei", sans-serif',
  literary: '"Noto Serif SC", "Songti SC", "SimSun", serif',
  rounded: '"SF Pro Rounded", "Hiragino Maru Gothic ProN", "YouYuan", sans-serif',
}
export type Appearance = { font: keyof typeof fonts; size: number; border: 'solid' | 'double' | 'dashed' | 'none'; color: string; radius: number }
export const defaults: Appearance = { font: 'modern', size: 15, border: 'solid', color: '#d8b6a4', radius: 20 }
export function sanitizeAppearance(value: unknown): Appearance {
  const v = value && typeof value === 'object' ? value as Partial<Appearance> : {}
  return {
    font: v.font && Object.hasOwn(fonts, v.font) ? v.font : defaults.font,
    size: typeof v.size === 'number' && Number.isFinite(v.size) ? Math.min(20, Math.max(13, v.size)) : defaults.size,
    border: ['solid', 'double', 'dashed', 'none'].includes(v.border || '') ? v.border! : defaults.border,
    color: typeof v.color === 'string' && /^#[0-9a-f]{6}$/i.test(v.color) ? v.color : defaults.color,
    radius: typeof v.radius === 'number' && Number.isFinite(v.radius) ? Math.min(28, Math.max(4, v.radius)) : defaults.radius,
  }
}
export function useChatAppearance() {
  let saved: unknown
  try { saved = JSON.parse(localStorage.getItem(appearanceKey) || 'null') } catch { /* A private browser still supports editing. */ }
  const appearance = ref(sanitizeAppearance(saved))
  const style = computed(() => ({ '--chat-font': fonts[appearance.value.font], '--chat-size': `${appearance.value.size}px`, '--sent-border': appearance.value.border, '--sent-color': appearance.value.color, '--sent-width': appearance.value.border === 'double' ? '3px' : '1px', '--sent-radius': `${appearance.value.radius}px` }))
  watch(appearance, value => { try { localStorage.setItem(appearanceKey, JSON.stringify(sanitizeAppearance(value))) } catch { /* Changes remain active for this visit. */ } }, { deep: true })
  function reset() { appearance.value = { ...defaults } }
  return { appearance, style, reset }
}
