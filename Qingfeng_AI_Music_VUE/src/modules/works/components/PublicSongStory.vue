<script setup lang="ts">
import { ref } from 'vue'
import { t } from '../../../shared/i18n'
import { getPublicStory, savePublicStory } from '../api/detail'
const props = defineProps<{songId: string}>()
const text = ref(''), saved = ref(''), notice = ref(''), busy = ref(false), loaded = ref(false)
async function load() {
  if (loaded.value || busy.value) return
  busy.value = true
  try { saved.value = text.value = (await getPublicStory(props.songId)).text; loaded.value = true; notice.value = '' }
  catch { notice.value = '音乐故事暂时无法读取。' }
  finally { busy.value = false }
}
async function publish() {
  if (busy.value || !loaded.value || text.value.trim() === saved.value) return
  busy.value = true
  try {
    saved.value = text.value = (await savePublicStory(props.songId, text.value)).text
    notice.value = text.value ? '音乐故事已公开。' : '已移除公开故事。'
  } catch { notice.value = '保存失败，请重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <details class="public-story-editor" @toggle="($event.target as HTMLDetailsElement).open && load()">
    <summary>{{ t('音乐故事') }}</summary>
    <label v-if="loaded">{{ t('展示在这首歌公开试听页的故事（选填）') }}
      <textarea v-model="text" :disabled="busy" maxlength="1500" rows="3" />
    </label>
    <button v-if="loaded" type="button" class="button secondary" :disabled="busy || text.trim() === saved" @click="publish">{{ t(text.trim() ? '发布音乐故事' : '移除公开故事') }}</button>
    <button v-else-if="notice" type="button" class="text-button" @click="load">{{ t('重试') }}</button>
    <p role="status">{{ t(notice) }}</p>
  </details>
</template>
<style scoped>
.public-story-editor { margin-block: .9rem; }
summary { cursor: pointer; font-weight: 650; }
label,textarea { display: block; width: 100%; margin-block: .55rem; }
textarea { padding: .55rem; font: inherit; }
</style>
