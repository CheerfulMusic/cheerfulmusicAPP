<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { locale } from '../../../shared/i18n'
import image from '../assets/inspiration-galaxy.png'
import { animateGalaxy } from '../model/galaxy-animation'

const en=computed(()=>locale.value==='en'), canvas=ref<HTMLCanvasElement>(), art=ref<HTMLImageElement>()
const loaded=ref(false), fast=ref(false), focused=ref(false)
const effects=ref<{id:number;kind:'leaf'|'ring';x:number;y:number;drift:number;delay:number}[]>([])
let sequence=0
const timers=new Set<ReturnType<typeof setTimeout>>()
function activate(event:MouseEvent,kind:'leaf'|'ring'){
  const zone=event.currentTarget as HTMLElement, scene=zone.parentElement!
  const box=scene.getBoundingClientRect(),target=zone.getBoundingClientRect()
  const x=event.detail?event.clientX:target.left+target.width/2, y=event.detail?event.clientY:target.top+target.height/2
  const ids=new Set<number>()
  for(let i=0;i<(kind==='leaf'?7:3);i++){
    const id=++sequence;ids.add(id)
    effects.value.push({id,kind,x:100*(x-box.left)/Math.max(1,box.width),y:100*(y-box.top)/Math.max(1,box.height),drift:Math.random()*54-16,delay:i*.11})
  }
  effects.value=effects.value.slice(-72)
  const timer=setTimeout(()=>{effects.value=effects.value.filter(e=>!ids.has(e.id));timers.delete(timer)},2700)
  timers.add(timer)
}
watch([canvas,art,loaded],([surface,picture,ready],_,cleanup)=>{
  if(!surface||!picture||!ready)return
  const motion=window.matchMedia('(prefers-reduced-motion: reduce)')
  let stop:(()=>void)|undefined
  function sync(){stop?.();stop=motion.matches?undefined:animateGalaxy(surface!,picture!,()=>fast.value||focused.value)}
  sync();motion.addEventListener('change',sync)
  cleanup(()=>{stop?.();motion.removeEventListener('change',sync)})
})
onBeforeUnmount(()=>timers.forEach(clearTimeout))
</script>
<template>
  <div class="galaxy-scene">
    <img ref="art" :src="image" alt="" @load="loaded=true"/>
    <canvas ref="canvas" aria-hidden="true"/>
    <button class="tree-zone" type="button" :aria-label="en?'Shake leaves from the tree':'轻摇树枝，落下树叶'" @click="activate($event,'leaf')"/>
    <button class="orbit-zone" type="button" :aria-label="en?'Spin the galaxy faster':'加速银河旋转'" @pointerenter="fast=true" @pointerleave="fast=false" @focus="focused=($event.currentTarget as HTMLElement).matches(':focus-visible')" @blur="focused=false"/>
    <button class="water-zone" type="button" :aria-label="en?'Ripple the lake':'轻点湖面，泛起涟漪'" @click="activate($event,'ring')"/>
    <i v-for="effect in effects" :key="effect.id" :class="effect.kind==='leaf'?'falling-leaf':'water-ring'" aria-hidden="true" :style="{left:effect.x+'%',top:effect.y+'%','--drift':effect.drift+'px',animationDelay:effect.delay+'s'}"/>
  </div>
</template>
<style scoped>
.galaxy-scene{position:relative;isolation:isolate;aspect-ratio:1674/940;overflow:hidden;background:#1d2632}
.galaxy-scene img,.galaxy-scene canvas{position:absolute;inset:0;width:100%;height:100%;display:block}
.galaxy-scene canvas{opacity:0;pointer-events:none}
.galaxy-scene button{position:absolute;appearance:none;padding:0;border:0;background:transparent;cursor:pointer}
.galaxy-scene button:focus-visible{outline:2px solid white;outline-offset:-3px}
.tree-zone{left:0;top:0;width:30%;height:46%;border-radius:0 0 95% 0}
.orbit-zone{left:51%;top:4%;width:47%;height:47%;border-radius:50%;transform:rotate(-14deg)}
.water-zone{left:20%;bottom:0;width:80%;height:26%}
.falling-leaf,.water-ring{position:absolute;pointer-events:none;opacity:0}
.falling-leaf{width:9px;height:5px;background:#f4c1aa;border-radius:90% 0 85% 0;animation:fall 1.9s ease-in both}
.water-ring{width:15px;height:7px;border:1.5px solid #fff7dc;border-radius:50%;box-shadow:0 0 4px #cbdfe9;animation:ripple 1.35s ease-out both}
@keyframes fall{0%{opacity:0}10%{opacity:1}65%{opacity:1;transform:translate(var(--drift),32px) rotate(110deg)}100%{opacity:0;transform:translate(calc(var(--drift) + 10px),65px) rotate(240deg)}}
@keyframes ripple{0%{opacity:0;transform:translate(-50%,-50%) scale(.3)}10%{opacity:1}100%{opacity:0;transform:translate(-50%,-50%) scale(6)}}
@media(prefers-reduced-motion:reduce){.falling-leaf,.water-ring{animation:none;opacity:.8}}
</style>
