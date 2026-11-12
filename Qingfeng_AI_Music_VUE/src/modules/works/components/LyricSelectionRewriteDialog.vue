<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import { guardCredits } from '../../../shared/credits/credit-gate'
import { useCredits } from '../../../shared/credits/useCredits'
import { rewriteLyricSelection } from '../api/detail'

const props = defineProps<{workId: string; workVersion: number; source: string; language: string}>()
const emit = defineEmits<{close: []; apply: [lyrics: string]; rewriteAll: []}>()
const dialog = ref<HTMLDialogElement>()
const previewHeading = ref<HTMLElement>()
const body = ref<HTMLElement>()
const kept = ref(new Set<number>())
const preview = ref<string>()
const previewing = ref(false)
const busy = ref(false)
const error = ref('')
const { credits, refreshCredits } = useCredits()
const previewCost = computed(() => {
  const current = credits.value
  if (!current) return ''
  return current.rewriteFreeRemaining !== 0 || current.rewriteCost === 0
    ? t('本次改写不消耗积分')
    : t('本次改写消耗 {cost} 积分', { cost: current.rewriteCost })
})
let requestId: string | undefined
let alive = true

type LyricRow = {index: number; text: string}
type LyricGroup = {title: string; lines: LyricRow[]}
const sourceLines = computed(() => props.source.split('\n'))
const isLyric = (line: string) => !!line.trim() && !/^\s*\[.*\]\s*$/.test(line) && !/[\r\n\u0085\u2028\u2029\v\f]/.test(line)
const groups = computed(() => {
  const result: LyricGroup[] = []
  let group: LyricGroup | undefined
  let title = ''
  sourceLines.value.forEach((line, index) => {
    const header = /^\s*\[(.*)\]\s*$/.exec(line)
    if (header) {
      group = undefined
      title = header[1]?.trim() ?? ''
    } else if (!line.trim()) {
      group = undefined
    } else if (isLyric(line)) {
      if (!group) {
        group = { title, lines: [] }
        result.push(group)
        title = ''
      }
      group.lines.push({ index, text: line })
    }
  })
  return result
})
const total = computed(() => groups.value.reduce((sum, group) => sum + group.lines.length, 0))
const canGenerate = computed(() => !busy.value && kept.value.size > 0 && kept.value.size < total.value)
const previewLines = computed(() => preview.value?.split('\n') ?? [])

function groupSelected(group: LyricGroup) {
  return group.lines.every(line => kept.value.has(line.index))
}

function changeSelection(lines: LyricRow[], checked: boolean) {
  const next = new Set(kept.value)
  for (const line of lines) {
    if (checked) next.add(line.index)
    else next.delete(line.index)
  }
  kept.value = next
  requestId = undefined
  preview.value = undefined
  error.value = ''
}

async function showPreview() {
  previewing.value = true
  await nextTick()
  if (body.value) body.value.scrollTop = 0
  previewHeading.value?.focus({ preventScroll: true })
}

async function generate() {
  if (!canGenerate.value) return
  if (preview.value) {
    await showPreview()
    return
  }
  busy.value = true
  error.value = ''
  try {
    if (!requestId) {
      const gate = await guardCredits(() => credits.value, refreshCredits, view => view.rewriteFreeRemaining === 0 ? view.rewriteCost : 0)
      if (!alive) return
      if (gate.blocked) {
        error.value = t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。', { balance: gate.balance ?? 0, cost: gate.cost })
        return
      }
      requestId = crypto.randomUUID()
    }
    const { lyrics } = await rewriteLyricSelection(props.workId, {
      requestId, workVersion: props.workVersion, lyrics: props.source, keptLines: [...kept.value], language: props.language,
    })
    if (!alive) return
    const candidate = lyrics.split('\n')
    const expected = sourceLines.value
    if (candidate.length !== expected.length || expected.some((line, index) => {
      const next = candidate[index]!
      return kept.value.has(index) || !isLyric(line) ? next !== line : !isLyric(next) || next.trim() === line.trim()
    })) throw new Error('重写结果不完整，当前歌词已保留，请重试。')
    preview.value = lyrics
    await showPreview()
  } catch (cause) {
    if (!alive) return
    // If a response was lost, retry the same preview instead of charging a second request.
    const retrySameRequest = cause instanceof ApiError && (cause.status === 0 || cause.status === 408
      || (!cause.code && cause.status >= 500) || cause.code === 'REWRITE_REQUEST_PENDING' || cause.code === 'REWRITE_GENERATION_BUSY')
    if (!retrySameRequest) requestId = undefined
    error.value = cause instanceof Error ? cause.message : '重写结果不完整，当前歌词已保留，请重试。'
  } finally {
    if (alive) {
      busy.value = false
      void refreshCredits().catch(() => {})
    }
  }
}

function close() {
  if (!busy.value) emit('close')
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => {
  alive = false
  dialog.value?.close()
})
</script>

<template>
  <dialog ref="dialog" class="lyric-selection-dialog" aria-labelledby="lyric-selection-title" @cancel.prevent="close">
    <header>
      <h2 id="lyric-selection-title">{{ t('保留选句，重写其余歌词') }}</h2>
      <button type="button" class="selection-close" :aria-label="t('关闭')" :disabled="busy" @click="close">×</button>
    </header>

    <div ref="body" class="selection-body">
      <template v-if="previewing">
        <h3 ref="previewHeading" tabindex="-1">{{ t('新版歌词预览') }}</h3>
        <p class="selection-help">{{ t('采用后将填回歌词编辑框，保存时才创建新版本。') }}</p>
      </template>
      <p v-else class="selection-help">{{ t('勾选要原样保留的歌词，也可以保留整段。') }}</p>
      <section v-for="(group, index) in groups" :key="group.lines[0]!.index" class="selection-section">
        <div class="selection-heading">
          <h4>{{ group.title || t('第 {count} 段', { count: index + 1 }) }}</h4>
          <label v-if="!previewing">
            <input type="checkbox" :checked="groupSelected(group)"
              :indeterminate.prop="!groupSelected(group) && group.lines.some(line => kept.has(line.index))"
              :disabled="busy" @change="changeSelection(group.lines, ($event.target as HTMLInputElement).checked)">
            {{ t('保留整段') }}
          </label>
        </div>
        <template v-if="previewing">
          <div v-for="line in group.lines" :key="line.index" class="selection-row selection-result" :class="{ 'is-kept': kept.has(line.index) }">
            <span>{{ previewLines[line.index] }}</span>
            <small>{{ kept.has(line.index) ? t('原样保留') : t('已重写') }}</small>
            <details v-if="!kept.has(line.index)">
              <summary>{{ t('查看原句') }}</summary>
              <p>{{ line.text }}</p>
            </details>
          </div>
        </template>
        <template v-else>
          <label v-for="line in group.lines" :key="line.index" class="selection-row" :class="{ 'is-kept': kept.has(line.index) }">
            <input type="checkbox" :checked="kept.has(line.index)" :disabled="busy"
              @change="changeSelection([line], ($event.target as HTMLInputElement).checked)">
            <span>{{ line.text }}</span>
            <small aria-hidden="true">{{ kept.has(line.index) ? t('保留') : t('待重写') }}</small>
          </label>
        </template>
      </section>
    </div>

    <footer>
      <p v-if="error" class="selection-error" role="alert">{{ t(error) }}</p>
      <div class="selection-footer-row">
        <div>
          <p aria-live="polite">{{ t('保留 {kept} 句 · 重写 {rewritten} 句', { kept: kept.size, rewritten: total - kept.size }) }}</p>
          <p v-if="!previewing && !preview && previewCost">{{ previewCost }}</p>
        </div>
        <div class="selection-actions">
          <template v-if="previewing">
            <button type="button" class="button secondary" @click="previewing = false">{{ t('返回选择') }}</button>
            <button type="button" class="button" @click="emit('apply', preview!)">{{ t('采用新版') }}</button>
          </template>
          <template v-else>
            <button type="button" class="button secondary" :disabled="busy" @click="emit('rewriteAll')">
              {{ credits && credits.lyricsCost > 0 ? t('全部重写（{count} 积分）', { count: credits.lyricsCost }) : t('全部重写') }}
            </button>
            <button type="button" class="button" :disabled="!canGenerate" @click="generate">{{ busy ? t('正在重写歌词…') : t('生成预览') }}</button>
          </template>
        </div>
      </div>
    </footer>
  </dialog>
</template>

<style scoped>
.lyric-selection-dialog {
  width:min(760px, calc(100% - 32px));
  max-height:90dvh;
  padding:0;
  border:1px solid var(--line);
  border-radius:20px;
  background:var(--surface);
  color:var(--ink);
  overflow:hidden;
  box-shadow:0 24px 80px #55313f29;
}
.lyric-selection-dialog[open] { display:flex; flex-direction:column; }
.lyric-selection-dialog::backdrop { background:#4b334c59; backdrop-filter:blur(5px); }
.lyric-selection-dialog header {
  display:flex; align-items:center; justify-content:space-between; gap:12px; padding:16px 22px; border-bottom:1px solid var(--line);
}
.lyric-selection-dialog h2 { margin:0; font-size:21px; }
.selection-close {
  flex:none; width:42px; height:42px; border:1px solid var(--line); border-radius:50%;
  background:var(--surface); color:var(--muted); font-size:23px;
}
.selection-body { min-height:0; overflow-y:auto; overscroll-behavior:contain; padding:16px 22px 22px; }
.selection-body h3 { margin:0 0 8px; font-size:18px; }
.selection-help { margin:0; color:var(--muted); font-size:13px; }
.selection-section { margin-top:18px; }
.selection-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:4px 0; }
.selection-heading h4 { margin:0; font-size:14px; }
.selection-heading label { display:flex; align-items:center; gap:6px; min-height:36px; color:var(--muted); font-size:13px; }
.selection-row {
  display:grid; grid-template-columns:auto minmax(0, 1fr) auto; align-items:center; gap:8px;
  padding:8px 9px; min-height:40px; border-top:1px solid var(--line); white-space:pre-wrap; overflow-wrap:anywhere;
}
label.selection-row { cursor:pointer; }
.selection-row.is-kept { background:var(--accent-soft); }
.selection-row small { color:var(--muted); white-space:nowrap; }
.selection-row.is-kept small { color:var(--accent-text); }
.selection-row input, .selection-heading input { width:16px; height:16px; margin:0; accent-color:var(--accent); }
.selection-result { grid-template-columns:minmax(0, 1fr) auto; }
.selection-result details { grid-column:1/-1; color:var(--muted); font-size:12px; }
.selection-result summary { cursor:pointer; }
.selection-result details p { margin:4px 0 0; }
.lyric-selection-dialog footer { padding:12px 22px; border-top:1px solid var(--line); background:var(--surface-raised); }
.selection-footer-row { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; }
.selection-footer-row p { margin:0; color:var(--muted); font-size:13px; }
.selection-error { margin:0 0 8px; color:var(--danger); font-size:13px; }
.selection-actions { display:flex; flex-wrap:wrap; gap:8px; }
.selection-actions button { min-height:42px; }
.selection-actions button:disabled { opacity:.5; }
@media(max-width:600px) {
  .lyric-selection-dialog header, .selection-body, .lyric-selection-dialog footer { padding-inline:14px; }
  .lyric-selection-dialog h2 { font-size:18px; }
  .selection-row, .selection-heading label { min-height:44px; }
}
</style>
