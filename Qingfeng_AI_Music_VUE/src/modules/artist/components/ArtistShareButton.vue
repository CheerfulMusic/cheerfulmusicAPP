<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { shareSongCard } from '../../works/model/song-sharing'

const props = defineProps<{ url: string; name: string }>()
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
  <div class="artist-share">
    <button type="button" class="artist-action" :disabled="sharing" @click="share">{{ t('分享主页') }}</button>
    <p v-if="copied" class="artist-share-status" role="status">{{ t('主页链接已复制。') }}</p>
    <label v-if="manual" class="artist-share-manual">
      {{ t('请手动复制主页链接') }}
      <input ref="manualInput" type="text" readonly :value="url" @focus="manualInput?.select()">
    </label>
  </div>
</template>

<style scoped>
.artist-share { min-width: 0; max-width: 100%; }
.artist-share-status { margin: 6px 0 0; font-size: .8rem; color: var(--muted); }
.artist-share-manual { display: grid; gap: 4px; margin-top: 6px; max-width: 260px; font-size: .8rem; }
.artist-share-manual input {
  min-width: 0; width: 100%; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px;
  background: var(--surface); color: var(--ink); font: inherit;
}
</style>
