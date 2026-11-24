<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onMounted } from 'vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { locale } from '../../../shared/i18n'
import { listWorks, type LibraryItem } from '../../works/api/works'
import { usePlayer } from '../../../shared/player/store'
import { coverUrl } from '../../works/api/covers'
import { audioUrl } from '../../works/api/detail'
import { librarySongTitle } from '../../works/model/song-title'
const props=defineProps<{refreshKey:string}>()
const emit=defineEmits<{open:[id:string]}>()
const player=usePlayer()
function track(row:LibraryItem) {
  return {id:row.audioId!,title:librarySongTitle(row),audio:audioUrl(row.work.id,row.audioId!),cover:coverUrl(row.work.id,row.coverId),provenance:row.audioOrigin??undefined}
}
function play(row:LibraryItem) {
  if(!row.audioId)return
  player.setQueue(items.value.filter(item=>item.audioId).map(track))
  void player.toggle(track(row))
}
function active(row:LibraryItem){return !!row.audioId && player.current?.id===row.audioId && (player.playing||player.starting)}
const items=ref<LibraryItem[]>([]),busy=ref(false),error=ref(false)
const tr=(zh:string,en:string)=>locale.value==='en'?en:zh
let sequence=0,disposed=false,timer:ReturnType<typeof setTimeout>|undefined
let page=0, hasNext=false
async function fetchPage(reset:boolean){
  if(!reset && (busy.value || !hasNext))return
  const version=++sequence, requested=reset?0:page+1
  busy.value=true;error.value=false
  try{
    const rows=await listWorks(requested)
    if(disposed||version!==sequence)return
    page=requested;hasNext=rows.length===20
    items.value=reset?rows:[...new Map([...items.value,...rows].map(row=>[row.work.id,row])).values()]
  }catch{if(!disposed&&version===sequence)error.value=true}
  finally{if(!disposed&&version===sequence)busy.value=false}
}
function refresh(){return fetchPage(true)}
function scrolled(event:Event){
  const list=event.currentTarget as HTMLElement
  if(list.scrollHeight-list.clientHeight-list.scrollTop<48)void fetchPage(false)
}
function changed(){items.value=[];sequence++;void refresh()}
watch(()=>props.refreshKey,()=>{clearTimeout(timer);timer=setTimeout(()=>void refresh(),400)},{immediate:true})
onMounted(()=>window.addEventListener('qingfeng:identity-changed',changed))
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(timer);window.removeEventListener('qingfeng:identity-changed',changed)})
</script>
<template>
  <section id="agent-library" tabindex="-1" class="producer-shelf">
    <header><h2><NavIcon name="headphones"/>{{ tr('我的作品','My songs') }}</h2></header>
    <p v-if="busy" role="status">{{ tr('正在加载…','Loading…') }}</p>
    <p v-if="error" role="status">{{ tr('作品加载失败','Could not load songs') }} <button @click="refresh">{{ tr('重试','Retry') }}</button></p>
    <div v-else-if="!busy&&!items.length" class="shelf-empty"><NavIcon name="music"/><p>{{ tr('还没有作品','No songs yet') }}</p><small>{{ tr('完成创作后，你的歌曲会出现在这里。','Your songs will appear here as you create them.') }}</small></div>
    <div class="shelf-list" @scroll="scrolled">
      <button v-for="item in items" :key="item.work.id" type="button" class="shelf-song" :class="{'is-playing':active(item)}"
        :aria-pressed="item.audioId ? active(item) : undefined" @click="item.audioId ? play(item) : emit('open',item.work.id)">
        <img :src="coverUrl(item.work.id,item.coverId)" alt=""/><span><strong>{{ librarySongTitle(item) }}</strong>
        <small>{{ item.audioId ? item.work.updatedAt.slice(0,10) : tr('查看作品','Open work') }}</small></span>
        <b><PlaybackIcon v-if="item.audioId" :playing="active(item)"/><NavIcon v-else name="arrow"/></b>
      </button>
    </div>
  </section>
</template>
