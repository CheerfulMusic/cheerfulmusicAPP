import { onBeforeUnmount, ref } from 'vue'
export function useMusicClicks() {
  const sparks = ref<{ id: number; x: number; y: number }[]>([])
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let next = 0
  function burst(event: MouseEvent) {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const target = event.target instanceof Element ? event.target.closest('button, a, .agent-bubble') : null
    if (!target || target.matches(':disabled,[aria-disabled="true"]')) return
    const box = target.getBoundingClientRect()
    const id = ++next
    sparks.value = [...sparks.value.slice(-5), { id, x: event.detail ? event.clientX : box.left + box.width / 2, y: event.detail ? event.clientY : box.top + box.height / 2 }]
    const timer = setTimeout(() => { sparks.value = sparks.value.filter(item => item.id !== id); timers.delete(timer) }, 700)
    timers.add(timer)
  }
  onBeforeUnmount(() => { timers.forEach(clearTimeout); sparks.value = [] })
  return { sparks, burst }
}
