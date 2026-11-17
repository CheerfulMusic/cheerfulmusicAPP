<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { formatDate, t } from '../../../shared/i18n'
import { decodeUploadImage, encodeUploadJpeg, imageUploadAccept } from '../../../shared/media/image-upload'
import { getUsage } from '../api/detail'
import { coverUrl, editCover, loadCovers, selectCover, uploadCover, type CoverState } from '../api/covers'

const props = defineProps<{ workId: string; state?: CoverState; published?: boolean }>()
const emit = defineEmits<{ updated: [state: CoverState]; saved: []; close: [] }>()
const titleId = useId()
const dialog = ref<HTMLDialogElement>()
const fileInput = ref<HTMLInputElement>()
const canvas = ref<HTMLCanvasElement>()
const image = shallowRef<Awaited<ReturnType<typeof decodeUploadImage>>>()
const zoom = ref(1)
const x = ref(50)
const y = ref(50)
const busy = ref(false)
const decoding = ref(false)
const loading = ref(false)
const error = ref('')
const prompt = ref('')
const remaining = ref<number>()
const currentCover = computed(() => coverUrl(props.workId, props.state?.currentCoverId))
const aiCovers = computed(() => props.state?.assets.filter(item => item.origin === 'AI' || item.origin === 'AUTO') ?? [])
const active = computed(() => props.state?.edits.some(edit =>
  ['QUEUED', 'SUBMITTING', 'RUNNING', 'SAVING', 'SUBMISSION_UNKNOWN'].includes(edit.status)))
const pendingKey = ref('')
const pendingSignature = ref('')
const unknown = ref(false)
const keyName = `qingfeng.cover-edit:${props.workId}`
let mounted = true
let imageRequest: AbortController | undefined
let uploadedId: string | undefined

try {
  const old = JSON.parse(sessionStorage.getItem(keyName) || 'null')
  if (old) {
    pendingKey.value = old.key
    pendingSignature.value = old.signature
    unknown.value = Boolean(old.unknown)
  }
} catch { /* 本机缓存不可用时仍可使用普通封面。 */ }

function draw() {
  const target = canvas.value
  const img = image.value?.element
  const ctx = target?.getContext('2d')
  if (!target || !img || !ctx) return
  const side = Math.min(img.naturalWidth, img.naturalHeight) / zoom.value
  const sx = (img.naturalWidth - side) * x.value / 100
  const sy = (img.naturalHeight - side) * y.value / 100
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, target.width, target.height)
  ctx.drawImage(img, sx, sy, side, side, 0, 0, target.width, target.height)
}

watch([zoom, x, y], () => {
  uploadedId = undefined
  draw()
})

async function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value) return
  error.value = ''
  imageRequest?.abort()
  const request = new AbortController()
  imageRequest = request
  decoding.value = true
  try {
    const next = await decodeUploadImage(file, request.signal)
    if (imageRequest !== request) {
      next.release()
      return
    }
    uploadedId = undefined
    image.value?.release()
    image.value = next
    zoom.value = 1
    x.value = 50
    y.value = 50
    await nextTick()
    draw()
  } catch (cause) {
    if (imageRequest === request) error.value = cause instanceof Error ? cause.message : '图片无法读取，请换一张重试'
  } finally {
    if (imageRequest === request) {
      imageRequest = undefined
      decoding.value = false
    }
  }
}

async function refreshCovers() {
  const state = await loadCovers(props.workId)
  if (mounted) emit('updated', state)
  return state
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { await refreshCovers() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '封面读取失败' }
  finally { loading.value = false }
}

function finish() {
  dialog.value?.close()
  emit('close')
}

function close() {
  if (!busy.value) finish()
}

async function apply(id: string | null) {
  if (!props.state || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const state = await selectCover(props.workId, id, props.state.version)
    if (mounted) {
      emit('saved')
      emit('updated', state)
      finish()
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '封面应用失败'
    await refreshCovers().catch(() => undefined)
  } finally { busy.value = false }
}

async function upload() {
  const target = canvas.value
  const version = props.state?.version
  if (!target || version === undefined || busy.value || decoding.value) return
  busy.value = true
  error.value = ''
  try {
    if (!uploadedId) {
      const file = await encodeUploadJpeg(target, 'cover.jpg')
      if (!mounted) return
      uploadedId = (await uploadCover(props.workId, file)).id
    }
    const state = await selectCover(props.workId, uploadedId, version)
    if (mounted) {
      emit('saved')
      emit('updated', state)
      finish()
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '封面上传失败'
    const state = await refreshCovers().catch(() => undefined)
    if (mounted && uploadedId && state?.currentCoverId === uploadedId) finish()
  } finally { busy.value = false }
}

async function refreshUsage() {
  try {
    const value = await getUsage()
    remaining.value = value.cover?.dailyRemaining
  } catch { /* 封面额度不可读时仍可上传图片。 */ }
}

async function process() {
  const id = props.state?.currentCoverId
  const text = prompt.value.trim()
  if (!id || !text || busy.value || active.value || !props.state?.aiAvailable) return
  busy.value = true
  error.value = ''
  const signature = JSON.stringify([id, text])
  if (pendingSignature.value !== signature) {
    pendingKey.value = ''
    pendingSignature.value = signature
    unknown.value = false
  }
  pendingKey.value ||= crypto.randomUUID()
  try {
    try {
      sessionStorage.setItem(keyName, JSON.stringify({ key: pendingKey.value, signature, unknown: true }))
    } catch { /* 本机缓存不可用时仍保留当前请求键。 */ }
    await editCover(props.workId, id, text, pendingKey.value)
    unknown.value = false
    pendingKey.value = ''
    try { sessionStorage.removeItem(keyName) } catch { /* 缓存不可用。 */ }
  } catch (cause) {
    unknown.value = true
    error.value = cause instanceof Error ? cause.message : '封面请求结果待核对，请刷新任务'
    return
  } finally { busy.value = false }
  await Promise.all([load(), refreshUsage()])
}

onMounted(() => {
  dialog.value?.showModal()
  if (!props.state) void load()
  void refreshUsage()
})
onBeforeUnmount(() => {
  mounted = false
  imageRequest?.abort()
  imageRequest = undefined
  image.value?.release()
  dialog.value?.close()
})
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="song-cover-dialog" :aria-labelledby="titleId" @cancel.prevent="close">
      <header class="cover-heading">
        <h2 :id="titleId">{{ t('更换封面') }}</h2>
        <button type="button" class="cover-close" :aria-label="t('关闭')" :disabled="busy" @click="close">×</button>
      </header>

      <div class="cover-preview">
        <canvas v-if="image" ref="canvas" width="1024" height="1024" :aria-label="t('封面裁剪预览')"/>
        <img v-else :src="currentCover" :alt="t('当前封面')">
        <div class="cover-controls">
          <input ref="fileInput" type="file" :accept="imageUploadAccept" hidden @change="choose">
          <button type="button" class="button secondary" :disabled="busy || decoding" autofocus @click="fileInput?.click()">
            {{ t(decoding ? '正在处理图片…' : '选择封面照片') }}
          </button>
          <small v-if="!image && !decoding" class="save-info">{{ t('JPG、PNG 或 WebP，原图最大 50 MB') }}</small>
          <template v-if="image">
            <label>{{ t('缩放') }}<input v-model.number="zoom" type="range" min="1" max="3" step="0.01" :disabled="busy"></label>
            <label>{{ t('水平位置') }}<input v-model.number="x" type="range" min="0" max="100" :disabled="busy"></label>
            <label>{{ t('垂直位置') }}<input v-model.number="y" type="range" min="0" max="100" :disabled="busy"></label>
            <button type="button" class="button" :disabled="busy || decoding || !state" @click="upload">
              {{ t(busy ? '正在保存…' : '保存封面') }}
            </button>
          </template>
          <template v-else-if="state">
            <button type="button" class="button" :disabled="busy || decoding" @click="apply(state.currentCoverId)">{{ t(busy ? '正在保存…' : '保存封面') }}</button>
            <button v-if="state.currentCoverId" type="button" class="text-button" :disabled="busy || decoding" @click="apply(null)">{{ t('恢复默认封面') }}</button>
          </template>
        </div>
      </div>
      <p v-if="published" class="save-info">{{ t('保存后将同步更新广场和歌手主页的歌曲封面。') }}</p>
      <p v-if="!state && loading" role="status">{{ t('正在读取封面…') }}</p>
      <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <button v-if="!state && !loading" type="button" class="button secondary" @click="load">{{ t('重试') }}</button>

      <details v-if="state" class="cover-ai">
        <summary>{{ t('AI加工封面') }}</summary>
        <div v-if="aiCovers.length" class="cover-choices">
          <button v-for="item in aiCovers" :key="item.id" type="button" class="cover-choice"
            :aria-pressed="state.currentCoverId === item.id" :disabled="busy || !!image" @click="apply(item.id)">
            <img :src="coverUrl(workId, item.id)" :alt="t('AI加工封面')">
            <span>{{ formatDate(item.createdAt) }}</span>
          </button>
        </div>
        <p class="save-info">{{ t('点击AI加工会将所选照片发送至KIE图片服务，并使用独立的封面加工额度。原图会保留，满意后再应用。') }}</p>
        <p v-if="!state.aiAvailable" class="notice">{{ t('AI封面暂不可用，可以先使用上传的图片') }}</p>
        <p v-if="remaining !== undefined" class="save-info">
          {{ remaining < 0 ? t('今日封面加工不限次数') : t('今日还可加工 {remaining} 次封面', { remaining }) }}
        </p>
        <label class="field">{{ t('封面加工要求') }}
          <textarea v-model="prompt" maxlength="1000" :disabled="busy || active"
            :placeholder="t('例如：保留人物，做成温暖的胶片封面，不添加文字')"></textarea>
        </label>
        <div class="actions">
          <button type="button" class="button secondary"
            :disabled="busy || loading || active || !!image || !state.aiAvailable || !state.currentCoverId || !prompt.trim()" @click="process">
            {{ t(busy ? '正在处理…' : unknown ? '使用原请求重试' : 'AI加工这张照片') }}
          </button>
          <button type="button" class="text-button" :disabled="busy || loading" @click="load(); refreshUsage()">{{ t('刷新封面任务') }}</button>
        </div>
        <div v-for="edit in state.edits" :key="edit.id" class="cover-task">
          <p>{{ t(edit.status === 'SUCCEEDED' ? '封面已完成，请在上方预览后选择'
            : edit.status === 'FAILED' ? '这次封面加工未完成，原图仍可使用。'
            : edit.status === 'SUBMISSION_UNKNOWN' ? '封面结果仍待核对，暂不重复提交'
            : '正在加工封面…') }}</p>
          <small>{{ formatDate(edit.createdAt) }}</small>
        </div>
      </details>
    </dialog>
  </Teleport>
</template>

<style scoped>
.song-cover-dialog {
  box-sizing: border-box;
  width: min(610px, calc(100% - 32px));
  max-height: calc(100dvh - 32px);
  padding: clamp(16px, 4vw, 28px);
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--surface, #fffafa);
  color: var(--ink);
  box-shadow: 0 22px 64px #39212638;
  overscroll-behavior: contain;
}
.song-cover-dialog::backdrop { background: #39272e80; }
.cover-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.cover-heading h2 { margin: 0; font-size: 22px; }
.cover-close { width: 40px; height: 40px; border: 1px solid var(--line); border-radius: 50%; font-size: 24px; line-height: 1; }
.cover-close:hover { background: var(--surface-raised); }
.cover-preview { display: grid; grid-template-columns: minmax(0, 220px) minmax(0, 1fr); align-items: center; gap: clamp(12px, 4vw, 24px); }
.cover-preview > :is(img, canvas) { display: block; width: 100%; aspect-ratio: 1; height: auto; object-fit: cover; border-radius: 12px; background: var(--surface-raised); }
.cover-controls { display: grid; justify-items: start; gap: 12px; min-width: 0; }
.song-cover-dialog .button {
  min-width: 0;
  min-height: 42px;
  padding: 9px 17px;
  border: 1px solid var(--accent);
  border-radius: 999px;
  background: var(--accent);
  color: var(--on-accent);
  font-weight: 650;
}
.song-cover-dialog .button.secondary { background: var(--surface-raised); color: var(--accent-text); border-color: var(--line); }
.song-cover-dialog .button.secondary:hover:not(:disabled) { border-color: var(--accent-border); background: var(--accent-soft); }
.song-cover-dialog .text-button { min-height: 40px; color: var(--accent-text); }
.cover-controls label { display: grid; gap: 2px; width: 100%; font-size: 13px; color: var(--muted); }
.cover-controls input[type='range'] { width: 100%; min-height: 24px; margin: 0; accent-color: var(--accent); }
.cover-ai { margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--line); }
.cover-ai summary { padding: 6px 0; font-weight: 600; cursor: pointer; color: var(--accent-text); }
.cover-ai textarea { min-height: 85px; }
.cover-ai .actions { flex-wrap: wrap; }
.cover-choices { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.cover-choice { width: 100px; padding: 5px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-raised); color: var(--muted); }
.cover-choice[aria-pressed='true'] { border-color: var(--accent); color: var(--accent-text); }
.cover-choice img { display: block; width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 6px; }
.cover-choice span { display: block; padding: 6px 2px; font-size: 11px; }
.cover-task { border-top: 1px solid var(--line); margin-top: 12px; padding-top: 10px; color: var(--muted); font-size: 13px; }
.cover-task p { margin: 0 0 4px; }
@media (max-width: 520px) {
  .cover-preview { grid-template-columns: 1fr; }
  .cover-preview > :is(img, canvas) { max-width: min(100%, 190px); margin-inline: auto; }
  .cover-controls > .button { width: 100%; }
}
</style>
