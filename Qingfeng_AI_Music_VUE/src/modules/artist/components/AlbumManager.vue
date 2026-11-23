<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { ApiError } from '../../../shared/http/api-client'
import { deleteAlbum, ownAlbums, ownerAlbumCover, saveAlbum, albumPath, type Album } from '../api/albums'
import { type ArtistProfile } from '../api/artist'

const props=defineProps<{profile: ArtistProfile}>()
const albums=ref<Album[]>([]), loaded=ref(false), busy=ref(false), notice=ref(''), error=ref('')
const editing=ref<Album>(), title=ref(''), description=ref(''), selected=ref<string[]>([])
const cover=ref<File>(), preview=ref(''), removeCover=ref(false)
const releases=computed(() => new Map(props.profile.songs.map(song=>[song.id,song])))
let selectedVersions = new Map<string,number>()
const canPublish=computed(() => props.profile.published && !!selected.value.length && !!title.value.trim())
const displayedCover=computed(() => preview.value || (!removeCover.value && editing.value?.coverKey ? ownerAlbumCover(editing.value) : ''))
function clearPreview() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  cover.value=undefined; preview.value=''
}
function selectCover(event: Event) {
  const input=event.target as HTMLInputElement, file=input.files?.[0]
  if (!file) return
  if (!['image/jpeg','image/png'].includes(file.type) || file.size>5*1024*1024) {
    error.value='请选择 5 MB 以内的 JPG 或 PNG 图片。'; input.value=''; return
  }
  clearPreview(); cover.value=file; preview.value=URL.createObjectURL(file); removeCover.value=false; error.value=''
}
async function load() {
  if (busy.value) return
  busy.value=true; error.value=''
  try { albums.value=await ownAlbums(); loaded.value=true }
  catch { error.value='专辑列表暂时无法读取。' }
  finally { busy.value=false }
}
function edit(album?: Album) {
  if (busy.value) return
  const entry=album ?? {id:crypto.randomUUID(), title:'', description:'', version:0, published:false, tracks:[], coverKey:null}
  editing.value=entry; title.value=entry.title; description.value=entry.description
  selected.value=entry.tracks.map(track=>track.id); clearPreview(); removeCover.value=false; notice.value=''; error.value=''
  selectedVersions = new Map([...props.profile.songs,...entry.tracks].map(track=>[track.id,track.version]))
}
function move(index: number, delta: number) {
  const destination=index+delta
  if (destination<0 || destination>=selected.value.length) return
  const order=[...selected.value]
  order.splice(destination,0,order.splice(index,1)[0]!)
  selected.value=order
}
async function save(published: boolean) {
  const entry=editing.value
  if (!entry || busy.value || !title.value.trim() || (published && !canPublish.value)) return
  busy.value=true; notice.value=''; error.value=''
  const body=new FormData()
  for (const [key,value] of Object.entries({title:title.value.trim(),description:description.value.trim(),
      version:String(entry.version),published:String(published),removeCover:String(removeCover.value)})) body.set(key,value)
  selected.value.forEach(id=>{
    body.append('tracks',id)
    body.append('versions',String(selectedVersions.get(id) ?? releases.value.get(id)?.version))
  })
  if (cover.value) body.set('cover',cover.value)
  try {
    const updated=await saveAlbum(entry.id,body)
    albums.value=[updated,...albums.value.filter(item=>item.id!==updated.id)]
    editing.value=undefined; clearPreview(); notice.value=published?'专辑已公开。':'专辑已保存为草稿。'
  } catch (cause) {
    error.value=cause instanceof ApiError && cause.status===409 ? '专辑已在其他窗口更新，请重新载入后编辑。' : '专辑未保存，请检查选曲和网络后重试。'
  } finally { busy.value=false }
}
async function remove(entry: Album) {
  if (busy.value || !window.confirm(t('删除专辑《{title}》？歌曲仍保留在作品库。',{title:entry.title}))) return
  busy.value=true; error.value=''
  try { await deleteAlbum(entry); albums.value=albums.value.filter(a=>a.id!==entry.id); notice.value='专辑已删除。' }
  catch { error.value='删除失败，请重新载入专辑。' }
  finally { busy.value=false }
}
onBeforeUnmount(clearPreview)
</script>
<template>
  <details class="album-manager profile-card" @toggle="($event.target as HTMLDetailsElement).open && !loaded && load()">
    <summary>{{ t('我的专辑') }}</summary>
    <p v-if="error" role="alert">{{ t(error) }} <button type="button" :disabled="busy" @click="editing=undefined;load()">{{ t('重新载入') }}</button></p>
    <p v-if="notice" role="status">{{ t(notice) }}</p>
    <div v-if="loaded && !editing">
      <button class="button secondary" type="button" :disabled="busy" @click="edit()">{{ t('新建专辑') }}</button>
      <ul class="album-items"><li v-for="album in albums" :key="album.id">
        <strong>{{ album.title }}</strong><small>{{ t(album.published?'已公开':'草稿') }} · {{ t('{count} 首歌曲',{count:album.tracks.length}) }}</small>
        <button type="button" :disabled="busy" @click="edit(album)">{{ t('编辑') }}</button>
        <RouterLink v-if="album.published && profile.published && profile.publicId && album.tracks.length" :to="albumPath(profile.publicId,album.id)">{{ t('查看专辑') }}</RouterLink>
        <button type="button" :disabled="busy" @click="remove(album)">{{ t('删除') }}</button>
      </li></ul>
    </div>
    <form v-if="editing" @submit.prevent="save(editing.published)">
      <fieldset :disabled="busy">
        <label>{{ t('专辑名称') }}<input v-model="title" maxlength="100" required /></label>
        <label>{{ t('专辑简介') }}<textarea v-model="description" maxlength="1200" rows="3" /></label>
        <label>{{ t('专辑封面') }}<input type="file" accept="image/jpeg,image/png" @change="selectCover" /></label>
        <div v-if="displayedCover" class="album-cover-preview"><img :src="displayedCover" :alt="t('专辑封面')" width="76" height="76">
          <button type="button" @click="clearPreview();removeCover=true">{{ t('使用歌曲封面') }}</button></div>
        <h3>{{ t('曲目顺序') }}</h3>
        <ol class="album-selection">
          <li v-for="(id,index) in selected" :key="id"><span>{{ releases.get(id)?.title || t('已下架') }}</span>
            <button type="button" :disabled="index===0" @click="move(index,-1)">{{ t('上移') }}</button>
            <button type="button" :disabled="index===selected.length-1" @click="move(index,1)">{{ t('下移') }}</button>
            <button type="button" @click="selected=selected.filter(item=>item!==id)">{{ t('移除') }}</button></li>
        </ol>
        <label>{{ t('从主页作品添加歌曲') }}<select value="" :disabled="selected.length>=100" @change="event=>{const input=event.target as HTMLSelectElement;if(input.value)selected.push(input.value);input.value=''}">
          <option value="">{{ t('选择歌曲') }}</option>
          <option v-for="song in profile.songs.filter(track=>!selected.includes(track.id))" :key="song.id" :value="song.id">{{ song.title }}</option>
        </select></label>
        <p v-if="!profile.published">{{ t('公开专辑前，请先公开音乐人主页。') }}</p>
        <div class="album-buttons">
          <button type="button" :disabled="!title.trim()" @click="save(false)">{{ t(editing.published?'撤回为草稿':'保存草稿') }}</button>
          <button class="button" type="button" :disabled="!canPublish" @click="save(true)">{{ t(editing.published?'保存公开专辑':'公开专辑') }}</button>
          <button type="button" @click="editing=undefined;clearPreview()">{{ t('取消') }}</button>
        </div>
      </fieldset>
    </form>
  </details>
</template>
<style scoped>
.album-manager { padding: 1.15rem; margin-block: 1rem; }
summary,h3 { font-weight: 700; } summary { cursor: pointer; font-size: 1.2rem; margin-bottom: .7rem; }
fieldset { border: 0; padding: 0; }
label { display: grid; gap: .35rem; margin: .8rem 0; }
input:not([type=file]),textarea,select { padding: .55rem; border: 1px solid #d9ced4; border-radius: .55rem; font: inherit; width: 100%; }
.album-items { list-style: none; padding: 0; } .album-items li,.album-buttons,.album-cover-preview { display: flex; align-items: center; gap: .6rem; flex-wrap: wrap; }
.album-items li,.album-selection li { padding: .6rem 0; border-bottom: 1px solid #e4dce2; }
.album-items small { margin-right: auto; } .album-selection { padding-left: 1.5rem; }
.album-selection button { margin-left: .4rem; }
button:not(.button) { min-height: 2.4rem; color: inherit; background: transparent; border: 1px solid #ded8e2; border-radius: .5rem; padding: .16rem .55rem; cursor: pointer; }
img { border-radius: .5rem; object-fit: cover; }
</style>
