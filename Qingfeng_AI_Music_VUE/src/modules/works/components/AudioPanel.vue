<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { computed, onMounted, onBeforeUnmount, ref, watch, watchEffect } from 'vue'
import type { Work } from '../../studio'
import AudioVersionNote from './AudioVersionNote.vue'
import { deleteAudioVersion, listAudio, audioUrl, type AudioAsset, type GenerationTask } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
import {getAudioSelection,selectAudio} from '../api/section-edits'
import { songSettings } from '../model/song-settings'
import { songBaseTitle, songVersionTitle } from '../model/song-title'
const props = defineProps<{ work: Work; title: string; tasks: GenerationTask[]; cover?:string }>()
const emit = defineEmits<{ selected: [asset: AudioAsset | null];compare:[assetId:string];'load-state':[state:'loading'|'ready'|'failed']; 'selected-version': [number | undefined] }>()
const assets = ref<AudioAsset[]>([]), selected = ref(''), error = ref(''), player = usePlayer()
let selectionVersion=0,epoch=0,alive=true
const reading=ref(false)
const listed = ref(false)
const deleting = ref('')
const versionNumbers = computed(() => {
 const byId = new Map<string, number>()
 if (listed.value) {
  // The server returns newest first. Assign numbers from the oldest recording.
  for (const item of assets.value.slice().reverse()) {
   if (item.origin === 'GENERATED') byId.set(item.id, item.versionNumber ?? byId.size + 1)
  }
 }
 return byId
})
watchEffect(() => emit('selected-version', versionNumbers.value.get(selected.value)))
function recordingTitle(item: AudioAsset) {
 if (item.origin === 'IMPORTED') return props.title
 const base = songBaseTitle(props.work.title, songSettings(item, props.tasks)?.title) || props.title
 return songVersionTitle(base, versionNumbers.value.get(item.id))
}
watch(
 [() => new Map(assets.value.map(item => [item.id, recordingTitle(item)])), () => props.cover || defaultCover()],
 ([titles, cover]) => {
  player.updateTitles(titles)
  const stale = new Set<string>()
  for (const track of [...player.tracks, player.current]) {
   if (track && titles.has(track.id) && track.cover !== cover && track.audio === audioUrl(props.work.id, track.id)) {
    stale.add(track.id)
   }
  }
  for (const id of stale) player.updateCover(id, cover)
 },
)
function show(asset:AudioAsset){selected.value=asset.id;emit('selected',asset)}
async function select(asset: AudioAsset) {
 if(deleting.value)return
 const turn=epoch
 try {
  const saved=await selectAudio(props.work.id,asset.id,selectionVersion)
  if(!alive||turn!==epoch)return
  selectionVersion=saved.version
  show(asset)
 } catch(e) {
  if(alive&&turn===epoch)error.value=e instanceof Error?e.message:'当前歌曲版本已改变，请刷新后再选择。'
 }
}
async function load(){
 if(reading.value||!alive)return
 const turn=++epoch,workId=props.work.id;reading.value=true;error.value='';emit('load-state','loading')
 const current=()=>alive&&turn===epoch&&workId===props.work.id
 let listOk=false,selectionOk=false,selectionDone=false,selectedAsset:AudioAsset|null=null
 function fallback(){
  if(!current()||!selectionDone||selectedAsset)return
  const first=assets.value.find(a=>!a.sectionEdit)
  if(first){show(first);emit('load-state','ready')}
 }
 // 已选成曲先到就先展示；版本列表慢或失败不能挡住试听。
 await Promise.allSettled([
  listAudio(workId).then(list=>{if(!current())return;listOk=true;assets.value=list;if(selectedAsset&&!list.some(a=>a.id===selectedAsset!.id))assets.value.unshift(selectedAsset);listed.value=true;fallback()}),
  getAudioSelection(workId).then(value=>{
   if(!current())return
   selectionOk=true;selectionVersion=value.version;selectedAsset=value.asset
   if(value.asset){if(!assets.value.some(a=>a.id===value.asset!.id))assets.value.unshift(value.asset);show(value.asset);emit('load-state','ready')}
  }).finally(()=>{if(current()){selectionDone=true;fallback()}})
 ])
 if(!current())return
 reading.value=false
 if(selected.value){emit('load-state','ready');if(!listOk||!selectionOk)error.value='部分歌曲信息暂时无法读取，已有歌曲仍可试听。'}
 else if(listOk&&selectionOk)emit('load-state','ready')
 else{error.value='音频暂时无法读取，你的作品仍然保留。';emit('load-state','failed')}
}
function noteUpdated(asset:AudioAsset){assets.value=assets.value.map(item=>item.id===asset.id?{...item,note:asset.note,noteVersion:asset.noteVersion}:item)}
async function remove(asset:AudioAsset){
 if(deleting.value)return
 if(selected.value===asset.id){error.value='请先切换到其他版本，再删除当前版本。';return}
 if(!window.confirm(t('删除这个歌曲版本？删除后将从版本列表移除，已有分享链接仍可访问。')))return
 const turn=epoch;deleting.value=asset.id;error.value=''
 try{
  await deleteAudioVersion(props.work.id,asset.id)
  if(!alive||turn!==epoch)return
  assets.value=assets.value.filter(item=>item.id!==asset.id)
  if(player.current?.id===asset.id)player.close()
  else player.setQueue(player.tracks.filter(item=>item.id!==asset.id),false)
 }catch(e){if(alive&&turn===epoch)error.value=e instanceof Error?e.message:'删除失败，请稍后重试。'}
 finally{if(alive&&turn===epoch)deleting.value=''}
}
onMounted(load)
onBeforeUnmount(()=>{alive=false;epoch++})
async function play(asset: AudioAsset) {
 if(asset.sectionEdit&&asset.id!==selected.value){emit('compare',asset.id);return}
 const turn=epoch
 await select(asset)
 if(!alive||turn!==epoch)return
 const track=(item:AudioAsset)=>({id:item.id,title:recordingTitle(item),cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,item.id),provenance:item.origin})
 player.setQueue(assets.value.filter(item=>!item.sectionEdit||item.id===selected.value).map(track),false)
 void player.toggle(track(asset))
}
</script>
<template>
  <section class="audio-manager">
    <p v-if="error" class="notice error" role="alert">{{ t(error) }} <button class="text-button" :disabled="reading || !!deleting" @click="load">{{ t('重新读取歌曲') }}</button></p>
    <div v-if="assets.length" class="song-version-list">
      <h3>{{ t('对比歌曲版本') }}</h3>
      <p class="save-info">{{ t('切换试听后，再选择你想送出的版本。') }}</p>
      <article v-for="item in assets" :key="item.id" :id="'audio-version-'+item.id" tabindex="-1" class="audio-row-item" :class="{selected:selected===item.id}">
        <label>
          <input type="radio" :checked="selected===item.id" :value="item.id" :disabled="!!deleting||(item.sectionEdit&&item.id!==selected)" @change="select(item)">
          <span>
            {{ item.origin === 'IMPORTED' ? t('导入音频 · 非AI生成') : recordingTitle(item) }}
            <small>
              <template v-if="item.sectionEdit">{{ t('局部修改版') }} · </template>{{ (item.byteSize/1024/1024).toFixed(2) }} MB · {{ formatDate(item.createdAt) }}
            </small>
          </span>
        </label>
        <div class="audio-version-footer">
          <AudioVersionNote :asset="item" :disabled="!!deleting" @updated="noteUpdated" />
          <div class="audio-actions">
          <button class="button secondary" :disabled="!!deleting" @click="play(item)">{{ item.sectionEdit&&item.id!==selected?t('对比修改版'):player.current?.id===item.id&&player.playing?t('暂停'):t('试听') }}</button>
          <button class="text-button audio-delete" :disabled="!!deleting" @click="remove(item)">{{ deleting===item.id?t('删除中…'):t('删除') }}</button>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
