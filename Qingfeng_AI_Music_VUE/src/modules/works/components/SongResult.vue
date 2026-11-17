<script setup lang="ts">
import MusicVideoEditor from './MusicVideoEditor.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import AudioPreparation from '../../../shared/player/AudioPreparation.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { downloadSong } from '../api/download'
import { t, defaultCover } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { audioUrl, type AudioAsset } from '../api/detail'
import type { Work } from '../../studio'
import SyncedLyrics from './SyncedLyrics.vue'
import type { EditHint } from '../model/section-edit'
import { songVersionTitle } from '../model/song-title'
const props=defineProps<{work:Work;asset:AudioAsset;title:string;version?:number;renameSong:(title:string)=>Promise<void>;lyrics?:string;language?:string|null;cover?:string;pending?:boolean;checking?:boolean;busy?:boolean;sectionOpen?:boolean;publicationLabel?:string;publicationAction:string;publicationBusy?:boolean;canReplacePublication?:boolean}>()
defineEmits<{gift:[];premium:[];publish:[];replacePublication:[];regenerate:[];cover:[];edit:[hint?:EditHint]}>()
const mvOpen=ref(false)
const player=usePlayer()
const downloadPath=computed(()=>`app/works/${encodeURIComponent(props.work.id)}/audio/${encodeURIComponent(props.asset.id)}/download`)
const moreActions = ref<HTMLDetailsElement>()
function closeMore(restoreFocus = false) {
  if (!moreActions.value?.open) return
  moreActions.value.open = false
  if (restoreFocus) moreActions.value.querySelector('summary')?.focus()
}
function outsideActions(event: PointerEvent) {
  if (event.target instanceof Node && !moreActions.value?.contains(event.target)) closeMore()
}
function leaveActions(event: FocusEvent) {
  if (event.relatedTarget instanceof Node && !moreActions.value?.contains(event.relatedTarget)) closeMore()
}
onMounted(() => document.addEventListener('pointerdown', outsideActions))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outsideActions))
watch(() => props.asset.id, () => closeMore())
const versionTitle = computed(() => songVersionTitle(props.title, props.version))
async function download() {
  const extension = props.asset.contentType === 'audio/wav' ? '.wav' : '.mp3'
  const title = versionTitle.value
  await downloadSong(downloadPath.value, audioUrl(props.work.id, props.asset.id, true, title), title + extension)
}
const track=computed(()=>({id:props.asset.id,title:versionTitle.value,cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,props.asset.id),provenance:props.asset.origin}))
function play(){player.setQueue([track.value],false);void player.toggle(track.value)}
async function playFrom(time:number){if(player.current?.id!==props.asset.id||!player.playing)await player.toggle(track.value);if(player.current?.id===props.asset.id)player.seek(time)}

const titleInput = ref<HTMLInputElement>()
const renameButton = ref<HTMLButtonElement>()
const editingTitle = ref(false)
const savingTitle = ref(false)
const titleDraft = ref('')
const titleError = ref('')
const titleSaved = ref(false)
async function beginRename() {
  titleDraft.value = props.title
  titleError.value = ''
  titleSaved.value = false
  editingTitle.value = true
  await nextTick()
  titleInput.value?.focus()
  titleInput.value?.select()
}
async function closeRename() {
  editingTitle.value = false
  await nextTick()
  renameButton.value?.focus({ preventScroll: true })
}
async function saveTitle() {
  if (savingTitle.value) return
  const title = titleDraft.value.trim()
  if (!title || title.length > 100) {
    titleError.value = title ? '歌名最多 100 个字符。' : '请先填写歌名。'
    return
  }
  if (title === props.title) {
    await closeRename()
    return
  }
  savingTitle.value = true
  titleError.value = ''
  try {
    await props.renameSong(title)
    titleSaved.value = true
    await closeRename()
  } catch (cause) {
    titleError.value = cause instanceof Error ? cause.message : '歌曲名称保存失败，请重试。'
  } finally {
    savingTitle.value = false
  }
}
</script>
<template>
  <section id="song-result" class="song-result" tabindex="-1" :aria-label="t('歌曲试听')">
    <div class="song-result-art">
      <img :src="cover||defaultCover()" :alt="t('歌曲封面')">
      <button class="result-play" :aria-label="player.current?.id===asset.id&&(player.playing||player.starting)?t('暂停'):t('试听歌曲')" @click="play">
        <span aria-hidden="true"><PlaybackIcon :playing="player.current?.id===asset.id&&(player.playing||player.starting)"/></span>
      </button>
      <button type="button" class="cover-change" aria-haspopup="dialog" @click="$emit('cover')">{{ t('更换封面') }}</button>
    </div>
    <div class="song-result-copy">
      <span class="completion-label">{{ asset.origin==='GENERATED'?t('你的专属歌曲，完成了。'):t('你的音乐礼物，准备好了。') }}</span>
      <div v-if="!editingTitle" class="song-title-row">
        <h2>{{ versionTitle }}</h2>
        <button v-if="asset.origin === 'GENERATED'" ref="renameButton" type="button" class="song-rename-button"
          :aria-label="t('修改歌名')" :title="t('修改歌名')" @click="beginRename">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l4 4M5 15l10-10a2.83 2.83 0 0 1 4 4L9 19l-5 1z"/></svg>
        </button>
      </div>
      <form v-else class="song-title-form" @submit.prevent="saveTitle" @keydown.esc.stop.prevent="!savingTitle && closeRename()">
        <label for="result-title-input">{{ t('歌曲名称') }}</label>
        <input id="result-title-input" ref="titleInput" v-model="titleDraft" type="text" maxlength="100"
          :disabled="savingTitle" :aria-invalid="!!titleError" :aria-describedby="titleError ? 'result-title-error' : undefined">
        <p v-if="titleError" id="result-title-error" class="song-title-error" role="alert">{{ t(titleError) }}</p>
        <div class="song-title-form-actions">
          <button class="button secondary" type="button" :disabled="savingTitle" @click="closeRename">{{ t('取消') }}</button>
          <button class="button" type="submit" :disabled="savingTitle">{{ t(savingTitle ? '正在保存…' : '保存名称') }}</button>
        </div>
      </form>
      <p v-if="titleSaved" class="song-title-saved" role="status">{{ t('歌名已更新。') }}</p>
      <p>{{ work.recipient?t('送给 {recipient}',{recipient:work.recipient}):t('我的创作') }}</p>
      <blockquote v-if="work.message">{{ work.message }}</blockquote>
      <AudioPreparation :track="track"/>
      <div class="song-actions">
        <div class="song-primary-actions">
          <button class="button" type="button" @click="$emit('gift')">{{ t('分享歌曲') }}</button>
          <button class="button secondary premium-studio-entry" type="button" aria-haspopup="dialog" @click="$emit('premium')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3v7m0 4v7M12 3v12m0 4v2M19 3v2m0 4v12M2 10h6v4H2zm7 5h6v4H9zm7-10h6v4h-6z"/></svg>
            <span>{{ t('定制化音乐制作') }}</span>
          </button>
        </div>
        <div class="song-secondary-actions">
          <button class="button secondary" type="button" :disabled="busy" @click="$emit('regenerate')">{{ pending?t('查看生成进度'):checking?t('核对生成进度'):asset.origin==='GENERATED'?t('调整音乐'):t('重新制作一首') }}</button>
          <button class="button secondary" type="button" @click="download">{{ t('下载') }}</button>
          <details ref="moreActions" class="song-more-actions" @focusout="leaveActions" @keydown.esc.stop.prevent="closeMore(true)">
            <summary class="button secondary">{{ t('更多') }} <span aria-hidden="true">⌄</span></summary>
            <div class="song-more-panel" @click="closeMore(true)">
              <button type="button" aria-haspopup="dialog" @click="mvOpen=true">{{ t('制作 MV') }}</button>
              <button type="button" :disabled="publicationBusy" @click="$emit('publish')">{{ t(publicationAction) }}</button>
              <button v-if="canReplacePublication" type="button" :disabled="publicationBusy" @click="$emit('replacePublication')">{{ t('发布当前版本') }}</button>
            </div>
          </details>
        </div>
        <p v-if="publicationLabel" class="publication-status" role="status">{{ t(publicationLabel) }}</p>
      </div>
      <p class="save-info">{{ work.recipient?t('先听一遍，再把这份心意送给 TA。'):t('先听一遍，继续打磨你的作品。') }}</p>
    </div>
    <SyncedLyrics :work-id="work.id" :asset-id="asset.id" :lyrics="lyrics" :language="language" :editable="asset.origin==='GENERATED'" :section-open="sectionOpen" @seek="playFrom" @edit="$emit('edit',$event)"/>
  </section>
  <MusicVideoEditor v-if="mvOpen" :key="asset.id" :work-id="work.id" :asset-id="asset.id" :title="title" @close="mvOpen=false"/>
</template>

<style scoped>
.song-actions { display: grid; gap: 10px; margin: 22px 0 10px; }
.song-primary-actions, .song-secondary-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.song-more-actions { position: relative; }
.song-more-actions summary { gap: 6px; list-style: none; cursor: pointer; }
.song-more-actions summary::-webkit-details-marker { display: none; }
.song-more-actions summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.song-more-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 5;
  width: max-content;
  min-width: 144px;
  padding: 6px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  box-shadow: 0 8px 24px #71444f18;
}
.song-more-panel button {
  display: block;
  width: 100%;
  min-height: 40px;
  padding: 9px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  text-align: left;
}
.song-more-panel button:hover, .song-more-panel button:focus-visible { background: var(--field-background); }
.publication-status {
  flex-basis: 100%;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.song-title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.song-rename-button {
  display: inline-grid;
  place-items: center;
  align-self: center;
  flex: 0 0 40px;
  height: 40px;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: var(--muted);
  background: var(--field-background);
}
.song-rename-button:hover { color: var(--accent-text); border-color: var(--accent-border); }
.song-rename-button svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.75; }
.song-title-form { display: grid; gap: 8px; margin: 14px 0; }
.song-title-form label { color: var(--muted); font-size: 13px; }
.song-title-form input {
  width: 100%;
  min-height: 43px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  font: inherit;
  background: var(--field-background);
  color: var(--ink);
}
.song-title-form-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.song-title-error { margin: 0; color: var(--danger); font-size: 13px; }
.song-result-copy .song-title-saved { color: var(--success); }
@media (max-width: 600px) {
  .song-primary-actions, .song-secondary-actions { justify-content: center; }
  .song-title-row, .song-title-form-actions { justify-content: center; }
}
.song-result-copy .button.premium-studio-entry {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-color: #c38a32;
  background: linear-gradient(120deg, #ffe9b5, #f3cd77);
  color: #704311;
  box-shadow: 0 3px 10px #b978221c;
  font-weight: 650;
}
.premium-studio-entry svg {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.premium-studio-entry span { min-width: 0; }
.song-result-copy .button.premium-studio-entry:hover {
  background: linear-gradient(120deg, #ffe19b, #edbc59);
  border-color: #a66b1b;
  color: #593207;
}
.premium-studio-entry:focus-visible { outline: 2px solid #93601a; outline-offset: 3px; }
</style>
