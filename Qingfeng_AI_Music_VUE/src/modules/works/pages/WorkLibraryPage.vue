<script setup lang="ts">
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { t, formatDate } from '../../../shared/i18n'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { deleteWork, listWorks, type LibraryItem } from '../api/works'
import { ApiError } from '../../../shared/http/api-client'
import {coverUrl} from '../api/covers'
import { audioUrl } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
const works=ref<LibraryItem[]>([]),error=ref(''),loading=ref(false),page=ref(0),filter=ref('all'),player=usePlayer()
const deleteTarget=ref<LibraryItem|null>(null),deleteDialog=ref<HTMLDialogElement>(),deleting=ref(false),deleteError=ref(''),completedWarning=ref(false),notice=ref('')
const removed=new Set<string>();let identityEpoch=0
const labels:Record<string,string>={LYRICS_DRAFT:'待完善歌词',READY_TO_GENERATE:'待生成歌曲',GENERATING:'生成中',CHECKING:'结果待核对',COMPLETED:'已完成',GENERATION_FAILED:'生成失败'}
const shown=computed(()=>works.value.filter(w=>filter.value==='all'||(filter.value==='completed'?w.status==='COMPLETED':['GENERATING','CHECKING'].includes(w.status))))
let alive=true,sequence=0
async function load(target=page.value,quiet=false){const turn=++sequence;if(!quiet)loading.value=true;error.value='';try{const found=await listWorks(target);if(alive&&turn===sequence){works.value=found.filter(item=>!removed.has(item.work.id));page.value=target}}catch(e){if(alive&&turn===sequence)error.value=e instanceof Error?e.message:'读取作品失败'}finally{if(turn===sequence)loading.value=false}}
async function askDelete(item:LibraryItem){deleteTarget.value=item;completedWarning.value=!!item.audioId||item.status==='COMPLETED';deleteError.value='';await nextTick();deleteDialog.value?.showModal()}
function closeDelete(){if(deleting.value)return;deleteDialog.value?.close();deleteTarget.value=null;deleteError.value=''}
async function confirmDelete(){
 const item=deleteTarget.value;if(!item||deleting.value)return
 const epoch=identityEpoch;deleting.value=true;deleteError.value=''
 try{await deleteWork(item.work.id,completedWarning.value);if(!alive||epoch!==identityEpoch)return
  removed.add(item.work.id);sequence++;loading.value=false;works.value=works.value.filter(w=>w.work.id!==item.work.id)
  if(player.current?.audio.includes(`/works/${item.work.id}/`))player.reset()
  player.setQueue(works.value.filter(w=>w.audioId).map(track));notice.value='作品已删除';deleteDialog.value?.close();deleteTarget.value=null
  await load(works.value.length===0&&page.value>0?page.value-1:page.value,true)
 }catch(e){if(!alive||epoch!==identityEpoch)return;if(e instanceof ApiError&&e.code==='WORK_DELETE_CONFIRMATION_REQUIRED'){completedWarning.value=true;deleteError.value='作品刚刚生成完成，请阅读警告后再次确认删除。'}else deleteError.value=e instanceof Error?e.message:'删除失败，请稍后重试。'}finally{if(epoch===identityEpoch)deleting.value=false}
}
function track(r:LibraryItem){return {id:r.audioId!,title:r.songTitle||r.work.title||(r.work.recipient?t('写给{recipient}的歌',{recipient:r.work.recipient}):t('我的原创歌曲')),cover:coverUrl(r.work.id,r.coverId),audio:audioUrl(r.work.id,r.audioId!),provenance:r.audioOrigin!}}
function play(item:LibraryItem){if(!item.audioId)return;player.setQueue(works.value.filter(w=>w.audioId).map(track));void player.toggle(track(item))}
const timer=window.setInterval(()=>{if(document.visibilityState==='visible'&&!loading.value&&works.value.some(w=>['GENERATING','CHECKING'].includes(w.status)))void load(page.value,true)},8000)
const visible=()=>{if(document.visibilityState==='visible')void load(page.value,true)}
const identityChanged=()=>{identityEpoch++;removed.clear();deleting.value=false;deleteDialog.value?.close();deleteTarget.value=null;notice.value='';works.value=[];filter.value='all';void load(0)}
onMounted(()=>{window.addEventListener('qingfeng:identity-changed',identityChanged);void load();document.addEventListener('visibilitychange',visible)})
onBeforeUnmount(()=>{window.removeEventListener('qingfeng:identity-changed',identityChanged);alive=false;sequence++;window.clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
</script>
<template><div class="creation-heading"><div><h1>{{ t('把每一份心意，留在歌里。') }}</h1><p class="backend-state">{{ t('故事、歌词和完成的歌曲，都在这里。') }}</p></div><RouterLink class="button" to="/create">{{ t('再写一首送给TA') }}</RouterLink></div><div class="library-toolbar"><div class="library-filters" role="group" :aria-label="t('筛选作品')"><button v-for="tab in [{id:'all',label:'全部作品'},{id:'completed',label:'已完成'},{id:'progress',label:'生成中'}]" :key="tab.id" :aria-pressed="filter===tab.id" :class="{active:filter===tab.id}" @click="filter=tab.id">{{ t(tab.label) }}</button></div><button class="text-button" :disabled="loading" @click="load()">{{ t('刷新作品') }}</button></div><p v-if="notice" class="notice" role="status">{{ t(notice) }}</p><p v-if="loading" class="notice" role="status">{{ t('正在读取作品…') }}</p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><div v-if="shown.length" class="works-grid"><article v-for="item in shown" :key="item.work.id" class="module-card work-item library-song"><div class="library-art"><img :src="coverUrl(item.work.id,item.coverId)" :alt="t('歌曲封面')"><button v-if="item.audioId" class="library-play" :aria-label="player.current?.id===item.audioId&&player.playing?t('暂停'):t('试听歌曲')" @click="play(item)"><PlaybackIcon :playing="player.current?.id===item.audioId&&player.playing"/></button><span class="work-status" :class="item.status.toLowerCase()">{{ t(labels[item.status]||'待完善歌词') }}</span></div><div class="library-song-copy"><h2>{{ item.songTitle||item.work.title||(item.work.recipient?t('写给{recipient}的歌',{recipient:item.work.recipient}):t('我的原创歌曲')) }}</h2><p class="meta">{{ t('送给 {recipient}',{recipient:item.work.recipient}) }} · {{ t(item.work.occasion) }}</p><p v-if="item.status==='GENERATION_FAILED'" class="summary">{{ t('这次创作未完成，打开作品查看详情。故事和歌词都已保留。') }}</p><p v-else-if="item.status==='CHECKING'" class="summary">{{ t('创作结果仍在核对，请打开作品查看，避免重复提交。') }}</p><p v-else-if="!item.audioId" class="summary">{{ item.work.message||item.work.story||t('这个故事，等你慢慢写。') }}</p><p class="meta">{{ t('更新于') }} {{ formatDate(item.work.updatedAt) }}</p><RouterLink class="button secondary" :to="`/works/${item.work.id}`">{{ item.audioId?t('试听与分享'):['GENERATING','CHECKING'].includes(item.status)?t('查看生成进度'):t('继续制作') }}</RouterLink><button class="library-delete" type="button" :disabled="deleting" @click="askDelete(item)">{{ t('删除作品') }}</button></div></article></div><div v-else-if="!loading&&!error" class="module-card"><h2>{{ works.length?t('这个分类还没有作品'):t('第一首歌，从你的故事开始。') }}</h2><p>{{ t('填写故事后会自动保存，随时都可以继续。') }}</p><RouterLink v-if="!works.length" class="button" to="/create">{{ t('制作一首歌') }}</RouterLink></div><div class="actions"><button v-if="page>0" class="button secondary" :disabled="loading" @click="load(page-1)">{{ t('上一页') }}</button><button v-if="works.length===20" class="button secondary" :disabled="loading" @click="load(page+1)">{{ t('下一页') }}</button></div><RouterLink class="recovery-reminder" to="/account">{{ t('换设备前，记得保存作品找回码。') }}</RouterLink><Teleport to="body"><dialog ref="deleteDialog" class="work-delete-dialog" aria-labelledby="work-delete-title" @cancel.prevent="closeDelete" @click="($event.target===deleteDialog)&&closeDelete()"><div v-if="deleteTarget"><p class="delete-eyebrow">{{ t('删除作品') }}</p><h2 id="work-delete-title">{{ t(completedWarning?'删除已完成的歌曲？':'删除这份作品？') }}</h2><p class="delete-song-title">{{ deleteTarget.songTitle||deleteTarget.work.title||t('我的原创歌曲') }}</p><p class="delete-warning" :class="{completed:completedWarning}">{{ t(completedWarning?'这首歌已经制作完成。删除后将无法从“我的音乐”找回，原分享链接也将失效。请先下载需要保留的歌曲。':'删除后将从“我的音乐”移除。已提交的制作任务可能继续完成，不会因删除而自动退款。') }}</p><p v-if="deleteError" class="notice error" role="alert">{{ t(deleteError) }}</p><div class="delete-actions"><button class="button secondary" :disabled="deleting" autofocus @click="closeDelete">{{ t('暂不删除') }}</button><button class="button delete-confirm" :disabled="deleting" @click="confirmDelete">{{ t(deleting?'正在删除…':'确认删除') }}</button></div></div></dialog></Teleport></template>



<style scoped>
.library-delete{display:block;margin-top:16px;padding:8px 12px;border:1px solid #edc6c2;border-radius:12px;background:transparent;color:#bc4236;font:inherit;cursor:pointer}
.library-delete:hover{background:#fff0ec}.library-delete:disabled{opacity:.5;cursor:wait}
.work-delete-dialog{width:min(520px,calc(100vw - 32px));max-height:90dvh;overflow:auto;box-sizing:border-box;padding:32px;border:1px solid #efd4cd;border-radius:26px;background:#fffaf8;color:#252122;box-shadow:0 24px 90px #37202033}
.work-delete-dialog::backdrop{background:#241c1b66;backdrop-filter:blur(4px)}
.delete-eyebrow{margin-top:0;color:#b55043}.work-delete-dialog h2{font-size:26px;margin:12px 0}
.delete-song-title{font-weight:600;overflow-wrap:anywhere}.delete-warning{line-height:1.8;color:#746461}.delete-warning.completed{padding:16px;background:#fff0e9;border-radius:14px;color:#9b3c2c;border:1px solid #efc5b9}
.delete-actions{display:flex;gap:12px;margin-top:24px;flex-wrap:wrap}.delete-actions button{flex:1;white-space:nowrap;min-height:48px;padding:12px 18px;border:1px solid #e9c9c0;border-radius:999px;background:#fffaf8;color:#66514c;font:inherit;font-weight:600;cursor:pointer}.delete-actions .delete-confirm{background:#c43d30;border-color:#c43d30;color:white}.delete-actions button:disabled{opacity:.55;cursor:wait}
</style>
