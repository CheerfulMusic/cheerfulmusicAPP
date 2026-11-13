<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { getSongPublication, publishSong, type SongPublication } from '../api/artist'

const props = defineProps<{ source: SongPublication; cover: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const selection = ref<Awaited<ReturnType<typeof getSongPublication>>>()
const loading = ref(true)
const saving = ref(false)
const done = ref(false)
const error = ref('')
const identical = computed(() => selection.value?.song?.assetId === props.source.assetId
  && selection.value.song.coverId === props.source.coverId && selection.value.song.title === props.source.title.trim())

async function load() {
  loading.value = true
  error.value = ''
  try { selection.value = await getSongPublication(props.source.workId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '暂时无法读取发布状态，请重试。' }
  finally { loading.value = false }
}
function close() {
  if (saving.value) return
  dialog.value?.close()
  emit('close')
}
async function submit() {
  if (!selection.value?.profileReady || identical.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await publishSong(props.source)
    done.value = true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '发布歌曲失败，请重试。' }
  finally { saving.value = false }
}
onMounted(() => {
  dialog.value?.showModal()
  void load()
})
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="artist-publish-dialog" aria-labelledby="publish-song-title" @cancel.prevent="close">
      <div class="artist-dialog-inner">
        <h2 id="publish-song-title">{{ t('发布歌曲') }}</h2>
        <div class="artist-song-snapshot">
          <img :src="cover" alt="">
          <div><strong>{{ source.title }}</strong><p>{{ t('当前试听版本') }}</p></div>
        </div>
        <p v-if="loading" role="status">{{ t('正在读取发布状态…') }}</p>
        <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
        <p v-if="done" role="status">{{ t(selection?.published ? '歌曲已展示到歌手主页。' : '歌曲已加入，主页公开后访客即可试听。') }}</p>
        <template v-else-if="selection">
          <h3>{{ t('展示到我的歌手主页') }}</h3>
          <p v-if="!selection.profileReady">{{ t('请先到「我的资料」保存艺名。') }}</p>
          <p v-else-if="identical">{{ t('这个版本已经加入主页作品。') }}</p>
          <p v-else-if="selection.song">{{ t('确认后，主页会改用当前试听的版本。') }}</p>
          <p v-else>{{ t(selection.published ? '确认后，访客可以在你的主页看到这首歌并试听。' : '确认后，这首歌将随歌手主页公开。') }}</p>
        </template>
        <div class="actions">
          <button type="button" class="button secondary" :disabled="saving" autofocus @click="close">{{ t(done || identical ? '完成' : '取消') }}</button>
          <RouterLink v-if="done || selection && !selection.profileReady" to="/profile" class="button" @click="close">{{ t('前往我的资料') }}</RouterLink>
          <button v-else-if="!loading && !selection" class="button" type="button" @click="load">{{ t('重试') }}</button>
          <button v-else-if="selection?.profileReady && !identical" class="button" type="button" :disabled="saving" @click="submit">
            {{ t(saving ? '正在发布…' : selection.song ? '更新发布版本' : '确认发布歌曲') }}
          </button>
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
