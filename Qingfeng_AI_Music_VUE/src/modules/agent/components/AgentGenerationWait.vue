<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { locale } from '../../../shared/i18n'
defineProps<{kind:'music'|'lyrics';cover?:string}>()
const seconds=ref(0)
let timer:ReturnType<typeof setInterval>
onMounted(()=>{const start=Date.now();timer=setInterval(()=>seconds.value=Math.floor((Date.now()-start)/1000),1000)})
onBeforeUnmount(()=>clearInterval(timer))
</script>
<template>
  <div class="generation-wait" role="status">
    <div class="wait-art" aria-hidden="true"><img v-if="kind==='music' && cover" :src="cover" alt=""/><span v-else>{{ kind==='lyrics'?'♬':'♫' }}</span></div>
    <div class="wait-copy"><strong>{{ kind==='lyrics'?(locale==='en'?'Writing lyrics…':'正在生成歌词…'):(locale==='en'?'Creating your song…':'正在生成歌曲…') }}</strong>
      <small>{{ kind==='lyrics'?(locale==='en'?'Shaping your ideas into lyrics':'正在根据创作方向编写歌词'):(locale==='en'?'Arranging and rendering audio':'正在编曲并生成音频') }}</small>
      <div class="wait-wave" aria-hidden="true"><i v-for="n in 15" :key="n" :style="{height:(5+18*Math.abs(Math.sin(n*1.8))*Math.sin(n*Math.PI/16))+'px',animationDelay:n*-.13+'s'}"/></div>
      <div class="wait-meter" aria-hidden="true"><i/></div>
    </div><small class="wait-clock" aria-hidden="true">{{ seconds }} s</small>
  </div>
</template>
<style scoped>
.generation-wait{display:flex;align-items:center;position:relative;gap:14px;width:min(100%,490px);box-sizing:border-box;padding:15px;border:1px solid #fff;border-radius:18px;background:linear-gradient(120deg,#fff,#f3f6f5 70%,#fbfcfb);box-shadow:0 6px 22px #86a69123;color:#304236}.wait-art{width:48px;height:48px;flex:none;display:grid;place-items:center;background:linear-gradient(145deg,#c4d3c9,#cbd8cf);border-radius:11px;color:white;font-size:29px}.wait-art img{width:100%;height:100%;object-fit:cover;border-radius:inherit}.wait-copy{position:relative;flex:1;min-width:0}.wait-copy strong,.wait-copy small{display:block;position:relative;z-index:1}.wait-copy small,.wait-clock{color:#678c74;font-size:11px}.wait-clock{align-self:flex-start;white-space:nowrap}.wait-wave{position:absolute;right:0;bottom:8px;display:flex;align-items:center;gap:2px;opacity:.32}.wait-wave i{width:3px;background:#a8bfb0;border-radius:3px;animation:pulse 1s infinite alternate}.wait-meter{height:4px;margin-top:9px;border-radius:8px;background:#e8eeea;overflow:hidden}.wait-meter i{display:block;width:35%;height:100%;background:linear-gradient(90deg,#a4bcac,#c5d4ca);animation:scan 1.8s ease-in-out infinite alternate}@keyframes scan{to{transform:translateX(180%)}}@keyframes pulse{to{transform:scaleY(.4)}}@media(prefers-reduced-motion:reduce){.wait-wave i,.wait-meter i{animation:none}}
</style>
