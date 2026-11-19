import { onBeforeUnmount, ref } from 'vue'
export function useMusicClicks() {
  const sparks = ref<{ id: number; x: number; y: number }[]>([])
  const timers = new Set<ReturnType<typeof setTimeout>>()
  let next = 0
  const trail=ref<{id:number;x:number;y:number;angle:number;length:number}[]>([])
  let last:{x:number;y:number;time:number}|undefined
  let fade:ReturnType<typeof setTimeout>
  function move(event:PointerEvent){
    if(event.pointerType!=='mouse'||window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)return
    const now=performance.now(),x=event.clientX,y=event.clientY
    if(last && now-last.time<16)return
    if(last && now-last.time<100){
      const dx=x-last.x,dy=y-last.y
      trail.value=[...trail.value.slice(-13),{id:++next,x:last.x,y:last.y,angle:Math.atan2(dy,dx),length:Math.hypot(dx,dy)}]
    }
    last={x,y,time:now};clearTimeout(fade);fade=setTimeout(()=>{trail.value=[];last=undefined},350)
  }
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
  onBeforeUnmount(() => { clearTimeout(fade);timers.forEach(clearTimeout); sparks.value = [] })
  return { sparks, burst, trail, move }
}
