<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef } from 'vue'
import { t } from '../../../shared/i18n'
import type { LyricRewriteAction } from '../api/detail'
import { lyricRewriteError, lyricsMaxLength, selectLyricLine, suggestLyricLine, type LyricLineSelection } from '../model/lyric-line-edit'

const props = defineProps<{ workId: string; language?: string | null; disabled: boolean }>()
const lyrics = defineModel<string>({ required: true })
const text = computed({
  get: () => lyrics.value.replace(/\r\n?/g, '\n'),
  set: value => { lyrics.value = value },
})
const editor = ref<HTMLTextAreaElement>()
const selection = shallowRef<LyricLineSelection>()
const suggestion = ref<string>()
const busy = ref(false)
const error = ref('')
let alive = true

const canSuggest = computed(() => !!selection.value && selection.value.source === text.value && !busy.value && !props.disabled)
const canApply = computed(() => {
  const selected = selection.value
  return canSuggest.value && !!selected && !!suggestion.value && text.value.length - selected.line.length + suggestion.value.length <= lyricsMaxLength
})

function captureSelection() {
  const field = editor.value
  if (!field) return
  const next = selectLyricLine(field.value, field.selectionStart, field.selectionEnd)
  const previous = selection.value
  if (next?.source === previous?.source && next?.start === previous?.start && next?.end === previous?.end) return
  selection.value = next
  suggestion.value = undefined
  error.value = ''
}

async function preview(action: LyricRewriteAction) {
  const chosen = selection.value
  if (!chosen || !canSuggest.value) return
  busy.value = true
  error.value = ''
  suggestion.value = undefined
  const current = () => alive && selection.value === chosen && text.value === chosen.source
  try {
    const language = props.language || (/[\u3400-\u9fff]/.test(chosen.line) ? 'zh-CN' : 'en')
    const result = await suggestLyricLine(props.workId, chosen, action, language)
    if (current()) suggestion.value = result
  } catch (cause) {
    if (current()) error.value = lyricRewriteError(cause)
  } finally {
    if (alive) busy.value = false
  }
}

async function applySuggestion() {
  const chosen = selection.value
  const replacement = suggestion.value
  if (!chosen || !replacement || !canApply.value) return
  text.value = chosen.source.slice(0, chosen.start) + replacement + chosen.source.slice(chosen.end)
  suggestion.value = undefined
  await nextTick()
  const caret = chosen.start + replacement.length
  editor.value?.focus({ preventScroll: true })
  editor.value?.setSelectionRange(caret, caret)
  captureSelection()
}

onBeforeUnmount(() => { alive = false })
</script>

<template>
  <h3><label for="remake-lyrics-input">{{ t('歌词') }}</label></h3>
  <p id="remake-lyrics-help" class="remake-lyrics-help">{{ t('可直接编辑，或点一句歌词进行润色。') }}</p>
  <textarea id="remake-lyrics-input" ref="editor" v-model="text" rows="9" :maxlength="lyricsMaxLength"
    :disabled="disabled" aria-describedby="remake-lyrics-help" @input="captureSelection" @click="captureSelection"
    @select="captureSelection" @keyup="captureSelection" />
  <div class="remake-lyrics-actions" role="group" :aria-label="t('选句润色')">
    <button type="button" class="button secondary" :disabled="!canSuggest" @click="preview('REWRITE')">{{ t('改写选句') }}</button>
    <button type="button" class="button secondary" :disabled="!canSuggest" @click="preview('RHYME')">{{ t('更押韵') }}</button>
    <button type="button" class="button secondary" :disabled="!canSuggest" @click="preview('COLLOQUIAL')">{{ t('更口语化') }}</button>
    <button type="button" class="button secondary" :disabled="!canSuggest" @click="preview('SHORTEN')">{{ t('缩短一句') }}</button>
    <span v-if="busy" role="status">{{ t('正在润色…') }}</span>
  </div>
  <p v-if="selection && !suggestion" class="remake-lyrics-selected"><strong>{{ t('已选歌词') }}</strong>{{ selection.line }}</p>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
  <section v-if="suggestion && selection" class="remake-lyrics-preview" :aria-label="t('改写建议')" aria-live="polite">
    <dl>
      <dt>{{ t('原句') }}</dt><dd>{{ selection.line }}</dd>
      <dt>{{ t('改写建议') }}</dt><dd>{{ suggestion }}</dd>
    </dl>
    <p v-if="!canApply && !disabled" class="remake-lyrics-help">{{ t('原歌词已变化或替换后超出长度，可以复制建议后手动修改。') }}</p>
    <div class="remake-lyrics-actions">
      <button type="button" class="button secondary" :disabled="!canApply" @click="applySuggestion">{{ t('采用这句') }}</button>
      <button type="button" class="text-button" @click="suggestion = undefined">{{ t('保留原句') }}</button>
    </div>
  </section>
</template>
