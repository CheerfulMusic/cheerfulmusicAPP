<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { useCredits } from '../../../shared/credits/useCredits'
import type { Work } from '../../studio'
import type { GenerationTask, Revision } from '../api/detail'
import { loadMusicCatalog, loadMusicSettings, loadTaskMusicSettings, musicBriefAvailable, previewWorkMusicSettings, suggestMusicBrief, type MusicCatalog, type MusicDirection, type MusicPreferences } from '../api/music-settings'
import { legacyPreferences, toggleMusicOption, validMusicPreferences } from '../model/music-preferences'
import MusicPreferenceGroup from './MusicPreferenceGroup.vue'
import RemakeLyricsEditor from './RemakeLyricsEditor.vue'

const props = defineProps<{ work: Work; busy: boolean; remake: { task: GenerationTask; revision: Revision }; submitError?: string }>()
const emit = defineEmits<{ apply: [value: { expectedVersion: number; preferences: MusicPreferences; lyrics: string }]; close: [] }>()

const dialog = ref<HTMLDialogElement>()
const pane = ref<'lyrics' | 'music'>('lyrics')
const lyrics = ref(props.remake.revision.lyrics)
const catalog = ref<MusicCatalog>()
const prefs = ref<MusicPreferences>(legacyPreferences(props.work))
const direction = ref<MusicDirection>()
const loading = ref(true)
const previewing = ref(false)
const error = ref('')
const hint = ref('')
const baseVersion = ref(props.work.version)
const { credits, refreshCredits } = useCredits()
const aiAvailable = ref(false)
const simplifying = ref(false)
const suggestion = ref<{ original: string; text: string }>()

const primaryGroupIds = ['genre', 'emotion', 'tempo']
const primaryGroups = computed(() => catalog.value?.groups.filter(group => primaryGroupIds.includes(group.id)) ?? [])
const extraGroups = computed(() => catalog.value?.groups.filter(group => !primaryGroupIds.includes(group.id)) ?? [])
const advancedSummary = computed(() => {
  const labels = extraGroups.value.flatMap(group => group.options
    .filter(option => prefs.value.choices[group.id]?.includes(option.id))
    .map(option => locale.value === 'en' ? option.en : option.zh))
  if (prefs.value.freeText.trim() || prefs.value.negativeText.trim()) labels.push(t('有补充要求'))
  return labels.join(' · ')
})

let alive = true
let previewTurn = 0
let timer: ReturnType<typeof setTimeout> | undefined
let initialized = false
const canApply = computed(() => !!direction.value && !previewing.value && !loading.value && !props.busy && !!lyrics.value.trim())

async function preview() {
  const turn = ++previewTurn
  previewing.value = true
  direction.value = undefined
  error.value = ''
  const current = () => alive && turn === previewTurn
  try {
    const value = await previewWorkMusicSettings(props.work.id, prefs.value)
    if (current()) {
      direction.value = value
      prefs.value.recipeId = value.preferences.recipeId
    }
  } catch (cause) {
    if (current()) error.value = cause instanceof Error ? cause.message : '音乐设置暂时无法读取，请重试。'
  } finally {
    if (current()) previewing.value = false
  }
}

watch(() => JSON.stringify({
  choices: prefs.value.choices, voice: prefs.value.vocalGender, free: prefs.value.freeText, negative: prefs.value.negativeText,
}), () => {
  if (!initialized) return
  clearTimeout(timer)
  previewTurn++
  direction.value = undefined
  previewing.value = true
  timer = setTimeout(() => { void preview() }, 350)
})
watch(() => props.work.version, version => { baseVersion.value = version })

function choose(group: string, option: string) {
  if (!catalog.value) return
  const next = toggleMusicOption(prefs.value, catalog.value, group, option)
  hint.value = next ? '' : '这个类别已选满，请先取消一个标签。'
  if (next) prefs.value = next
}

function changeVoice(event: Event) {
  const voice = (event.target as HTMLSelectElement).value as MusicPreferences['vocalGender']
  prefs.value = { ...prefs.value, vocalGender: voice, recipeId: null }
}

async function simplify() {
  const original = prefs.value.freeText
  if (simplifying.value || props.busy || !original.trim()) return
  simplifying.value = true
  suggestion.value = undefined
  hint.value = ''
  try {
    const value = await suggestMusicBrief(original)
    if (alive && prefs.value.freeText === original) suggestion.value = value
  } catch {
    if (alive) hint.value = 'AI整理暂不可用，原要求仍然保留，你可以继续调整或直接生成歌曲。'
  } finally {
    if (alive) simplifying.value = false
  }
}

function useSuggestion() {
  if (!suggestion.value || props.busy) return
  if (prefs.value.freeText === suggestion.value.original) {
    prefs.value.freeText = suggestion.value.text
    hint.value = '已使用精简建议，请确认后保存音乐设置。'
  } else {
    hint.value = '要求已发生变化，请重新整理后确认。'
  }
  suggestion.value = undefined
}

function close() {
  if (!props.busy) emit('close')
}

function apply() {
  if (!canApply.value || !direction.value) return
  const preferences = JSON.parse(JSON.stringify(direction.value.preferences)) as MusicPreferences
  emit('apply', { expectedVersion: baseVersion.value, preferences, lyrics: lyrics.value })
}

onMounted(async () => {
  dialog.value?.showModal()
  void refreshCredits().catch(() => {})
  void musicBriefAvailable().then(value => { if (alive) aiAvailable.value = value }).catch(() => {})
  try {
    const [options, saved, original] = await Promise.all([
      loadMusicCatalog(), loadMusicSettings(props.work.id), loadTaskMusicSettings(props.work.id, props.remake.task.id),
    ])
    if (!alive) return
    catalog.value = options
    baseVersion.value = saved.work.version
    const historical = original?.preferences
    if (historical && validMusicPreferences(historical, options) && Object.keys(historical.choices).length) {
      prefs.value = historical
    } else {
      // 旧版快照可能只有曲风和人声，没有结构化选项。
      const task = props.remake.task
      const voice = task.vocalGender === 'MALE' || task.vocalGender === 'FEMALE' ? task.vocalGender : 'AUTO'
      const legacy = legacyPreferences({ ...saved.work, style: task.style || '自动推荐', vocalGender: voice })
      prefs.value = validMusicPreferences(legacy, options)
        ? legacy
        : legacyPreferences({ ...saved.work, style: '自动推荐', vocalGender: voice })
      hint.value = '原版设置记录不完整，请确认新版的音乐偏好。'
    }
    await nextTick()
    if (!alive) return
    initialized = true
    await preview()
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '音乐设置暂时无法读取，请重试。'
  } finally {
    if (alive) loading.value = false
  }
})

onBeforeUnmount(() => {
  alive = false
  clearTimeout(timer)
  dialog.value?.close()
})
</script>

<template>
  <dialog ref="dialog" id="music-settings" class="music-settings-dialog" aria-labelledby="music-settings-title" @cancel.prevent="close">
    <header class="music-settings-header">
      <div><span>{{ remake.revision.title }}</span><h2 id="music-settings-title">{{ t('调整音乐') }}</h2></div>
      <button type="button" class="music-settings-close" :disabled="busy" :aria-label="t('关闭音乐设置')" @click="close">×</button>
    </header>

    <nav class="music-settings-tabs" :aria-label="t('调整区域')">
      <button type="button" :aria-pressed="pane === 'lyrics'" aria-controls="music-lyrics-pane" @click="pane = 'lyrics'">{{ t('歌词') }}</button>
      <button type="button" :aria-pressed="pane === 'music'" aria-controls="music-options-pane" @click="pane = 'music'">{{ t('音乐设置') }}</button>
    </nav>
    <div class="music-settings-layout" :data-pane="pane">
      <section id="music-lyrics-pane" class="music-settings-lyrics" :aria-label="t('歌词')">
        <RemakeLyricsEditor v-model="lyrics" :work-id="work.id" :language="remake.revision.language" :disabled="busy" />
      </section>
      <section id="music-options-pane" class="music-settings-controls" aria-labelledby="music-options-title">
        <h3 id="music-options-title">{{ t('音乐设置') }}</h3>
        <p v-if="loading" class="notice" role="status">{{ t('正在读取音乐选项…') }}</p>
        <fieldset v-if="catalog" class="music-preferences" :disabled="busy || loading">
          <label class="music-settings-voice">
            {{ t('演唱声音') }}
            <select :value="prefs.vocalGender" @change="changeVoice">
              <option value="AUTO">{{ t('自动') }}</option>
              <option value="MALE">{{ t('男声') }}</option>
              <option value="FEMALE">{{ t('女声') }}</option>
            </select>
          </label>
          <MusicPreferenceGroup v-for="group in primaryGroups" :key="group.id" :group="group"
            :selected="prefs.choices[group.id]" :disabled="busy" @choose="choose" />
          <details class="music-settings-more">
            <summary>
              <strong>{{ t('更多音乐设置') }}</strong>
              <span v-if="advancedSummary">{{ advancedSummary }}</span>
            </summary>
            <MusicPreferenceGroup v-for="group in extraGroups" :key="group.id" :group="group"
              :selected="prefs.choices[group.id]" :disabled="busy" @choose="choose" />
            <label class="field">{{ t('其他需要避开的元素') }}<input v-model="prefs.negativeText" maxlength="200" :placeholder="t('例如：不要刺耳的高音')"></label>
            <label class="field">{{ t('补充音乐要求') }}<textarea v-model="prefs.freeText" maxlength="300" rows="3" :placeholder="t('例如：像平时聊天一样自然，人声突出，伴奏简单。')" /></label>
            <div v-if="aiAvailable" class="music-brief-assist">
              <button type="button" class="text-button" :disabled="simplifying || !prefs.freeText.trim()" @click="simplify">{{ simplifying ? t('正在精简音乐要求…') : t('AI精简补充要求') }}</button>
              <small>{{ t('先预览再采用，不会修改歌词或自动替换原要求。') }}</small>
            </div>
            <section v-if="suggestion" class="notice" :aria-label="t('精简建议')">
              <strong>{{ t('精简建议') }}</strong><p>{{ suggestion.text }}</p>
              <small>{{ t('请确认曲风、乐器和限制均已保留。') }}</small>
              <div class="actions">
                <button type="button" class="button secondary" @click="useSuggestion">{{ t('使用精简版') }}</button>
                <button type="button" class="text-button" @click="suggestion = undefined">{{ t('保留原要求') }}</button>
              </div>
            </section>
          </details>
        </fieldset>
      </section>
    </div>

    <div v-if="hint || direction?.compilation?.notice || error || submitError" class="music-settings-notices">
      <p v-if="hint" class="music-settings-hint" role="status">{{ t(hint) }}</p>
      <p v-if="direction?.compilation?.notice" class="music-settings-hint" role="status">{{ t(direction.compilation.notice) }}</p>
      <p v-if="error || submitError" class="music-settings-error" role="alert">{{ t(error || submitError || '') }}</p>
    </div>
    <footer class="music-settings-footer">
      <p>
        {{ t('本次设置和歌词用于整首新版。') }}
        <template v-if="credits && credits.musicCost > 0">{{ t('每次歌曲生成消耗 {count} 积分', { count: credits.musicCost }) }}</template>
      </p>
      <div>
        <button type="button" class="button secondary" :disabled="busy" @click="close">{{ t('暂不修改') }}</button>
        <button type="button" class="button" :disabled="!canApply" @click="apply">{{ busy ? t('正在准备歌曲…') : t('生成整首新版') }}</button>
      </div>
    </footer>
  </dialog>
</template>
