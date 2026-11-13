<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { rhymeLyricLine, type LyricRhymeEnding, type LyricRhymeResult } from '../api/detail'
import { isLyricLine, lyricRewriteError } from '../model/lyric-line-edit'
import { rhymeLanguage, type LyricRhymeSection } from '../model/lyric-rhyme'

const props = defineProps<{
  workId: string
  section: LyricRhymeSection
  language?: string | null
  disabled?: boolean
  manualOnly?: boolean
  maxReplacementLength?: number
}>()
const emit = defineEmits<{ apply: [text: string]; close: [] }>()
const reference = ref<number>()
const allowNear = ref(true)
const allowCrossLanguage = ref(false)
const busy = ref(false)
const result = ref<LyricRhymeResult>()
const error = ref('')
const referenceSelect = ref<HTMLSelectElement>()
const original = computed(() => props.section.lines[props.section.lineIndex]!)
const language = computed(() => rhymeLanguage(props.section, props.language))
const canGenerate = computed(() => props.section.lines.length > 1 && !busy.value && !props.disabled
  && (!props.manualOnly || reference.value !== undefined))
const referenceOptions = computed(() => props.section.lines.map((line, index) => ({ line, index }))
  .filter(option => option.index !== props.section.lineIndex))
const statuses = {
  CHOOSE_REFERENCE: '请选择想和这句押韵的参考句。',
  ALREADY_RHYMES: '原句已与参考句押韵。',
  NO_MATCH: '暂时没有合适的押韵改写。可以更换参考句或保留原句。',
  UNKNOWN_PRONUNCIATION: '暂时无法确认韵脚读音，可以换一句参考。',
  SUGGESTIONS: '',
}
const matchLabels = { EXACT: '同韵', NEAR: '近韵', CROSS_LANGUAGE: '跨语言近韵' }
let alive = true

function clearResult() {
  result.value = undefined
  error.value = ''
}

function endingText(ending?: LyricRhymeEnding | null) {
  return ending?.sound ? `${ending.word} · ${ending.sound}` : ending?.word ?? '—'
}

function canApply(text: string) {
  return !props.disabled && !busy.value && text.trim() !== original.value && isLyricLine(text)
    && text.length <= (props.maxReplacementLength ?? 160)
}

async function generate() {
  if (!canGenerate.value) return
  clearResult()
  busy.value = true
  try {
    const response = await rhymeLyricLine(props.workId, {
      requestId: crypto.randomUUID(),
      line: original.value,
      lines: props.section.lines,
      lineIndex: props.section.lineIndex,
      referenceIndex: reference.value,
      language: language.value,
      allowNear: allowNear.value,
      allowCrossLanguage: allowCrossLanguage.value,
    })
    if (!alive) return
    result.value = response
  } catch (cause) {
    if (alive) error.value = lyricRewriteError(cause)
  } finally {
    if (alive) busy.value = false
  }
}

onMounted(() => {
  referenceSelect.value?.focus({ preventScroll: true })
  if (!props.manualOnly) void generate()
})
onBeforeUnmount(() => { alive = false })
</script>

<template>
  <section class="lyric-rhyme-editor" :aria-label="t('更押韵')">
    <header class="rhyme-heading">
      <strong>{{ t('更押韵') }}</strong>
      <button type="button" class="text-button" @click="emit('close')">{{ t('关闭') }}</button>
    </header>
    <p v-if="section.lines.length < 2" role="status">{{ t('本段只有一句歌词，添加一句后可选择参考句。') }}</p>
    <template v-else>
      <label class="rhyme-reference">
        <span>{{ t('参考句') }}</span>
        <select ref="referenceSelect" v-model="reference" :disabled="busy || disabled" @change="clearResult">
          <option :value="undefined" :disabled="manualOnly">{{ t(manualOnly ? '选择参考句' : '自动找参考句') }}</option>
          <option v-for="{ line, index } in referenceOptions" :key="index" :value="index">
            {{ t('第 {number} 句', { number: index + 1 }) }} · {{ line }}
          </option>
        </select>
      </label>
      <div class="rhyme-controls">
        <label><input v-model="allowNear" type="checkbox" :disabled="busy || disabled" @change="clearResult">{{ t('允许近韵') }}</label>
        <label v-if="language === 'mixed'"><input v-model="allowCrossLanguage" type="checkbox" :disabled="busy || disabled" @change="clearResult">{{ t('允许跨语言近韵') }}</label>
        <button type="button" class="button secondary rhyme-generate" :disabled="!canGenerate" @click="generate">{{ t('生成押韵建议') }}</button>
      </div>
      <p v-if="busy" role="status">{{ t('正在找韵脚和改写建议…') }}</p>
      <p v-else-if="error" role="alert">{{ t(error) }}</p>
      <div v-else-if="result" class="rhyme-result" aria-live="polite">
        <dl v-if="result.referenceIndex != null" class="rhyme-comparison">
          <div><dt>{{ t('参考句') }}</dt><dd>{{ section.lines[result.referenceIndex] }} <mark v-if="result.reference">{{ endingText(result.reference) }}</mark></dd></div>
          <div><dt>{{ t('原句') }}</dt><dd>{{ original }} <mark v-if="result.original">{{ endingText(result.original) }}</mark></dd></div>
        </dl>
        <p v-if="statuses[result.status]" role="status">{{ t(statuses[result.status]) }}</p>
        <ul v-if="result.candidates?.length" class="rhyme-candidates">
          <li v-for="candidate in result.candidates" :key="candidate.text">
            <span class="rhyme-candidate-text">{{ candidate.text }}</span>
            <span class="rhyme-candidate-ending"><mark>{{ endingText(candidate.ending) }}</mark> {{ t(matchLabels[candidate.match]) }}</span>
            <button type="button" class="text-button" :disabled="!canApply(candidate.text)" @click="emit('apply', candidate.text)">{{ t('采用这句') }}</button>
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<style scoped>
.lyric-rhyme-editor{display:grid;gap:10px;min-width:0;margin:12px 0;padding:14px;border:1px solid var(--line);border-radius:14px;background:var(--paper);color:var(--ink);font-size:.88rem}
.rhyme-heading,.rhyme-controls{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px 14px}
.rhyme-reference{display:grid;gap:5px;min-width:0}
.rhyme-reference select{display:block;width:100%;min-width:0;min-height:36px;padding:6px 10px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink);font:inherit}
.rhyme-controls{justify-content:flex-start}
.rhyme-controls label{display:inline-flex;align-items:center;gap:5px}
.rhyme-generate{margin-left:auto;padding:6px 12px;min-height:34px;font-size:inherit}
.lyric-rhyme-editor p{margin:0;line-height:1.5}
.rhyme-result{display:grid;gap:8px}
.rhyme-comparison{display:grid;gap:4px;margin:0}
.rhyme-comparison div{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:8px}
.rhyme-comparison dt{color:var(--muted)}
.rhyme-comparison dd{margin:0;overflow-wrap:anywhere}
.lyric-rhyme-editor mark{display:inline-block;border-radius:6px;padding:0 5px;background:var(--accent-soft);color:var(--accent-text)}
.rhyme-candidates{display:grid;gap:6px;padding:0;margin:0;list-style:none}
.rhyme-candidates li{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:start;gap:2px 10px;padding:7px 9px;border:1px solid var(--line);border-radius:8px}
.rhyme-candidate-text{overflow-wrap:anywhere}
.rhyme-candidate-ending{grid-row:2;color:var(--muted);font-size:.8rem}
.rhyme-candidates button{grid-column:2;grid-row:1/3;align-self:center}
</style>
