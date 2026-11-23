import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
export function useMusicClicks() {
  const sparks = ref<{ id: number; x: number; y: number }[]>([])
  const timers = new Set<ReturnType<typeof setTimeout>>()
  const layerTarget = shallowRef<Element | string>('body')
  function placeLayer(event: Event) {
    layerTarget.value = event.target instanceof Element ? event.target.closest('dialog[open]') ?? 'body' : 'body'
  }
  let next = 0
  const trail=ref<{id:number;x:number;y:number;angle:number;length:number}[]>([])
  let last:{x:number;y:number;time:number}|undefined
  let fade:ReturnType<typeof setTimeout>
  function move(event:PointerEvent){
    if(event.pointerType!=='mouse'||window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)return
    placeLayer(event)
    const now=performance.now(),x=event.clientX,y=event.clientY
    if(last && now-last.time<16)return
    if(last && now-last.time<100){
      const dx=x-last.x,dy=y-last.y
      trail.value=[...trail.value.slice(-13),{id:++next,x:last.x,y:last.y,angle:Math.atan2(dy,dx),length:Math.hypot(dx,dy)}]
    }
    last={x,y,time:now};clearTimeout(fade);fade=setTimeout(()=>{trail.value=[];last=undefined},350)
  }
  function burst(event: MouseEvent) {
    if (event.button !== 0) return
    placeLayer(event)
    let x = event.clientX, y = event.clientY
    if (event.type === 'click' && event.target instanceof Element) {
      const box = event.target.getBoundingClientRect()
      x = box.left + box.width / 2
      y = box.top + box.height / 2
    }
    const id = ++next
    sparks.value = [...sparks.value.slice(-5), { id, x, y }]
    const timer = setTimeout(() => { sparks.value = sparks.value.filter(item => item.id !== id); timers.delete(timer) }, 700)
    timers.add(timer)
  }
  function keyboardClick(event: MouseEvent) {
    if (!event.detail) burst(event)
  }
  onMounted(() => {
    document.addEventListener('pointerdown', burst, true)
    document.addEventListener('pointermove', move, true)
    document.addEventListener('click', keyboardClick, true)
  })
  onBeforeUnmount(() => {
    document.removeEventListener('pointerdown', burst, true)
    document.removeEventListener('pointermove', move, true)
    document.removeEventListener('click', keyboardClick, true)
    clearTimeout(fade)
    timers.forEach(clearTimeout)
  })
  return { sparks, trail, layerTarget }
}
