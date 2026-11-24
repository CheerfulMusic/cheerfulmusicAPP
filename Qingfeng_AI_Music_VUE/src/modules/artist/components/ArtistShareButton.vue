<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { t } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import { shareSongCard } from '../../works/model/song-sharing'

const props = defineProps<{ url: string; name: string; album?: boolean; song?: boolean }>()
const label = computed(() => props.song ? '分享' : props.album ? '分享专辑' : '分享主页')
const copiedLabel = computed(() => props.song ? '歌曲链接已复制。' : props.album ? '专辑链接已复制。' : '主页链接已复制。')
const manualLabel = computed(() => props.song ? '请手动复制歌曲链接' : props.album ? '请手动复制专辑链接' : '请手动复制主页链接')
const sharing = ref(false)
const copied = ref(false)
const manual = ref(false)
const manualInput = ref<HTMLInputElement>()

async function share() {
  if (sharing.value) return
  sharing.value = true
  copied.value = false
  manual.value = false
  try {
    const result = await shareSongCard({ title: props.name, url: props.url })
    copied.value = result === 'copied'
  } catch {
    manual.value = true
    await nextTick()
    manualInput.value?.focus()
  } finally {
    sharing.value = false
  }
}
</script>

<template>
  <div class="artist-share" :class="{'artist-share-song': song}">
    <button type="button" class="artist-action" :disabled="sharing" @click="share"><NavIcon v-if="song" name="share"/>{{ t(label) }}</button>
    <p v-if="copied" class="artist-share-status" role="status">{{ t(copiedLabel) }}</p>
    <label v-if="manual" class="artist-share-manual">
      {{ t(manualLabel) }}
      <input ref="manualInput" type="text" readonly :value="url" @focus="manualInput?.select()">
    </label>
  </div>
</template>

<style scoped>
.artist-share { min-width: 0; max-width: 100%; }
.artist-share-song button svg { width: 20px; height: 20px; flex: none; }
.artist-share-status { margin: 6px 0 0; font-size: .8rem; color: var(--muted); }
.artist-share-manual { display: grid; gap: 4px; margin-top: 6px; max-width: 260px; font-size: .8rem; }
.artist-share-manual input {
  min-width: 0; width: 100%; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px;
  background: var(--surface); color: var(--ink); font: inherit;
}
</style>
