<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { getSongPublication, ownerTrack, publishSong, publishSongToDiscover, type ArtistRelease, type SongPublication } from '../api/artist'

const props = defineProps<{ source: SongPublication; cover: string; discover?: boolean }>()
const emit = defineEmits<{
  close: []
  change: [selection: Awaited<ReturnType<typeof getSongPublication>>]
  'published-to-discover': [song: ArtistRelease]
}>()
const dialog = ref<HTMLDialogElement>()
const selection = ref<Awaited<ReturnType<typeof getSongPublication>>>()
const loading = ref(true)
const saving = ref(false)
const discover = ref(Boolean(props.discover))
const notice = ref('')
const error = ref('')
const identical = computed(() => selection.value?.song?.assetId === props.source.assetId
  && selection.value.song.coverId === props.source.coverId && selection.value.song.title === props.source.title.trim())
const artist = computed(() => selection.value?.publicId)
const needsProfile = computed(() => selection.value && !selection.value.published)
const needsRelease = computed(() => discover.value && selection.value && !selection.value.song)
const discoverSupported = computed(() => Boolean(artist.value && typeof selection.value?.song?.onDiscover === 'boolean'))
const discoverUnavailable = computed(() => discover.value && selection.value?.published && selection.value.song && !discoverSupported.value)
const confirmDiscover = computed(() => discover.value && discoverSupported.value && !selection.value?.song?.onDiscover)
const complete = computed(() => discover.value ? Boolean(selection.value?.song?.onDiscover) : identical.value)
const profileRoute = computed(() => ({ name: 'artist-profile', query: { publishWork: props.source.workId } }))
const discoverSong = computed(() => discover.value && selection.value?.song ? ownerTrack(selection.value.song) : null)

async function load() {
  loading.value = true
  error.value = ''
  selection.value = undefined
  try {
    const value = await getSongPublication(props.source.workId)
    selection.value = value
    emit('change', value)
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '暂时无法读取发布状态，请重试。' }
  finally { loading.value = false }
}
function close() {
  if (saving.value) return
  dialog.value?.close()
  emit('close')
}
async function submit() {
  const current = selection.value
  if (!current?.published || saving.value) return
  if (discover.value ? !confirmDiscover.value : identical.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const song = discover.value && current.song
      ? await publishSongToDiscover(props.source.workId, current.song.version)
      : await publishSong(props.source)
    notice.value = song.onDiscover ? '歌曲已发布到发现。' : '歌曲已公开到歌手主页。'
    selection.value = { ...current, song }
    emit('change', selection.value)
    if (song.onDiscover) emit('published-to-discover', song)
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '暂时无法更新歌曲展示状态，请重试。'
    await load()
    error.value = message
  }
  finally { saving.value = false }
}
function cancel() {
  if (discover.value && !props.discover && !complete.value && !saving.value) {
    discover.value = false
    error.value = ''
  } else close()
}
onMounted(() => {
  dialog.value?.showModal()
  void load()
})
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="artist-publish-dialog" aria-labelledby="publish-song-title" @cancel.prevent="cancel">
      <div class="artist-dialog-inner">
        <h2 id="publish-song-title">{{ t(discover ? '发布到发现' : identical ? '歌曲展示' : '公开到主页') }}</h2>
        <div class="artist-song-snapshot">
          <img :src="discoverSong?.cover || cover" alt="">
          <div><strong>{{ discoverSong?.title || source.title }}</strong><p>{{ discover ? selection?.stageName : t('当前试听版本') }}</p></div>
        </div>
        <p v-if="loading" role="status">{{ t('正在读取发布状态…') }}</p>
        <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
        <p v-if="notice" role="status">{{ t(notice) }}</p>
        <template v-if="selection && !loading">
          <p v-if="discoverUnavailable" role="status">{{ t('当前服务版本暂不支持发布到「发现」。请在功能上线后再试。') }}</p>
          <p v-else-if="needsProfile">{{ t(selection.profileReady ? '请先在「我的资料」公开歌手主页，再回来公开这首歌。' : '请先在「我的资料」保存艺名并公开歌手主页。') }}</p>
          <p v-else-if="needsRelease">{{ t('这首歌需要先公开到歌手主页，才能发布到「发现」。') }}</p>
          <p v-else-if="confirmDiscover">{{ t('发布后，所有访客都能在「发现」看到并试听这首歌。') }}</p>
          <p v-else-if="discover && !notice">{{ t('这首歌已发布到发现。') }}</p>
          <p v-else-if="!identical && selection.song?.onDiscover">{{ t('更新主页版本会同时撤回「发现」中的旧版本。更新后，你可以发布新版本。') }}</p>
          <p v-else-if="!identical">{{ t(selection.song ? '确认后，主页会改用当前试听的版本。' : '确认后，访客可以在你的主页看到这首歌并试听。') }}</p>
          <p v-else-if="!notice">{{ t(selection.song?.onDiscover ? '这首歌已发布到发现。' : '这首歌已在主页展示。') }}</p>
        </template>
        <div class="actions">
          <button type="button" class="button secondary" :disabled="saving" autofocus @click="cancel">
            {{ t(complete ? '完成' : '取消') }}
          </button>
          <template v-if="!loading">
            <RouterLink v-if="needsProfile && !discover" :to="profileRoute" class="button" @click="close">{{ t('前往我的资料') }}</RouterLink>
            <button v-else-if="!selection" class="button" type="button" @click="load">{{ t('重试') }}</button>
            <button v-else-if="!needsProfile && (confirmDiscover || !discover && !identical)" class="button" type="button" :disabled="saving" @click="submit">
              {{ t(saving ? '正在保存…' : confirmDiscover ? '确认发布到发现' : selection.song ? '更新主页版本' : '确认公开到主页') }}
            </button>
            <template v-else-if="complete && !needsProfile">
              <RouterLink v-if="artist && !discover" :to="{ name: 'artist-home', params: { id: artist } }" class="button secondary" @click="close">{{ t('查看主页') }}</RouterLink>
              <RouterLink v-if="selection.song?.onDiscover" to="/discover" class="button" @click="close">{{ t('查看发现') }}</RouterLink>
              <button v-else class="button" type="button" @click="discover = true; notice = ''">{{ t('发布到发现') }}</button>
            </template>
          </template>
        </div>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.artist-publish-dialog {
  width: min(calc(100% - 28px), 480px);
  max-height: calc(100% - 28px);
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 0;
  color: var(--ink);
  background: var(--surface);
}
.artist-publish-dialog::backdrop { background: #20111d88; }
.artist-dialog-inner { padding: clamp(18px, 5vw, 28px); }
.artist-dialog-inner h2 { margin: 0 0 18px; }
.artist-dialog-inner h3 { margin: 20px 0 6px; font-size: 1.05rem; }
.artist-dialog-inner p { margin: 6px 0 14px; color: var(--muted); }
.artist-song-snapshot { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 14px; align-items: center; background: var(--accent-soft); padding: 12px; border-radius: 10px; overflow-wrap: anywhere; }
.artist-song-snapshot img { width: 60px; height: 60px; object-fit: cover; border-radius: 5px; }
.artist-song-snapshot p { margin: 4px 0 0; }
.artist-dialog-inner .actions { margin-top: 22px; }
</style>
