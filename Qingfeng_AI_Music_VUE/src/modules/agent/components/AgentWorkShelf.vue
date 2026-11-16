<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onMounted } from 'vue'
import { locale, defaultCover } from '../../../shared/i18n'
import { listWorks, type LibraryItem } from '../../works/api/works'
const props=defineProps<{refreshKey:string;disabled:boolean}>()
defineEmits<{select:[id:string];more:[]}>()
const items=ref<LibraryItem[]>([]),busy=ref(false),error=ref(false)
const tr=(zh:string,en:string)=>locale.value==='en'?en:zh
let sequence=0,disposed=false,timer:ReturnType<typeof setTimeout>|undefined
async function refresh(){const version=++sequence;busy.value=true;error.value=false;try{const rows=await listWorks(0);if(!disposed&&version===sequence)items.value=rows.slice(0,5)}catch{if(!disposed&&version===sequence)error.value=true}finally{if(!disposed&&version===sequence)busy.value=false}}
function changed(){items.value=[];sequence++;void refresh()}
watch(()=>props.refreshKey,()=>{clearTimeout(timer);timer=setTimeout(()=>void refresh(),400)},{immediate:true})
onMounted(()=>window.addEventListener('qingfeng:identity-changed',changed))
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(timer);window.removeEventListener('qingfeng:identity-changed',changed)})
</script>
<template><aside class="producer-shelf" :aria-label="tr('我的作品','My songs')"><h2>▥ {{ tr('我的作品','My songs') }}</h2><p v-if="busy" role="status">{{ tr('正在加载…','Loading…') }}</p><p v-if="error" role="status">{{ tr('作品暂时加载失败','Unable to load songs') }} <button @click="refresh">{{ tr('重试','Retry') }}</button></p><p v-else-if="!busy&&!items.length">{{ tr('你的第一首歌，从这里开始。','Your first song starts here.') }}</p><button v-for="item in items" :key="item.work.id" class="shelf-song" :disabled="disabled" @click="$emit('select',item.work.id)"><img :src="item.coverId?`/api/app/works/${encodeURIComponent(item.work.id)}/covers/${encodeURIComponent(item.coverId)}`:defaultCover()" alt=""/><span><strong>{{ item.songTitle||item.work.title||tr('未命名作品','Untitled song') }}</strong><small>{{ item.work.updatedAt.slice(0,10) }}</small></span></button><button class="shelf-more" :disabled="disabled" @click="$emit('more')">{{ tr('查看更多','View more') }} ›</button></aside></template>
<style scoped>
.producer-shelf{padding:20px 12px;border:1px solid white;border-radius:24px;background:#ffffffa6;align-self:start}.producer-shelf h2{font-size:14px;color:#493166;margin:0 0 18px}.producer-shelf p{font-size:12px;color:#9785a7;line-height:1.8}.shelf-song{display:flex;align-items:center;gap:9px;border:0;background:transparent;text-align:left;padding:10px 0;width:100%;cursor:pointer;color:#4e3e69}.shelf-song img{width:40px;height:40px;border-radius:9px;object-fit:cover}.shelf-song span{min-width:0}.shelf-song strong{display:block;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.shelf-song small{font-size:10px;color:#a28bbb}.shelf-more{width:100%;margin-top:14px;padding:10px;border:0;border-radius:12px;color:#9671c9;background:#ece1fa;cursor:pointer}
</style>
