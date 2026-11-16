<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { platform } from '../../../platform'
import { t } from '../../../shared/i18n'
import {
  cropBounds, cropSize, decodeProfileImage, defaultCrop, drawProfileCrop, encodeProfileCrop, profileImageAccept,
  type CropPosition, type ProfileImageKind,
} from '../model/profile-image'

const props = defineProps<{ kind: ProfileImageKind; source?: File | string; initialPosition?: CropPosition }>()
const emit = defineEmits<{ close: []; apply: [file: File, source: File | string, position: CropPosition] }>()
const dialog = ref<HTMLDialogElement>()
const fileInput = ref<HTMLInputElement>()
const canvas = ref<HTMLCanvasElement>()
const loaded = shallowRef<Awaited<ReturnType<typeof decodeProfileImage>>>()
const position = ref({ ...defaultCrop })
const error = ref('')
const loading = ref(false)
const applying = ref(false)
const drawn = ref(false)
const dragOver = ref(false)
const titleId = useId()
const instructionsId = useId()
const isAvatar = computed(() => props.kind === 'avatar')
const size = computed(() => cropSize[props.kind])
const clamp = (value: number) => Math.max(0, Math.min(1, value))
let source: File | string | undefined
let request: AbortController | undefined
let drag: { id: number; x: number; y: number; position: CropPosition } | undefined
let alive = true

async function storedImage(url: string, controller: AbortController) {
  const timer = setTimeout(() => controller.abort(), 12_000)
  try {
    const response = await fetch(url, {
      credentials: platform.kind === 'web' ? 'same-origin' : 'include',
      signal: controller.signal,
    })
    if (response.ok) return await response.blob()
  } catch { /* The user can choose another file if the saved image is unavailable. */ }
  finally { clearTimeout(timer) }
  throw new Error('无法读取已保存的图片，请重新选择。')
}

async function load(value: File | string, initial = defaultCrop) {
  if (applying.value) return
  request?.abort()
  const controller = new AbortController()
  request = controller
  loading.value = true
  error.value = ''
  try {
    const blob = typeof value === 'string' ? await storedImage(value, controller) : value
    const image = await decodeProfileImage(blob, controller.signal)
    if (request !== controller) {
      image.release()
      return
    }
    loaded.value?.release()
    loaded.value = image
    source = value
    position.value = { ...initial }
  } catch (cause) {
    if (request === controller) error.value = cause instanceof Error ? cause.message : '图片处理失败，请重新选择图片。'
  } finally {
    if (request === controller) {
      request = undefined
      loading.value = false
    }
  }
}

function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void load(file)
}

function onDragOver(event: DragEvent) {
  if (applying.value || !event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'copy'
  dragOver.value = true
}

function leaveDropArea(event: DragEvent) {
  if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) dragOver.value = false
}

function drop(event: DragEvent) {
  dragOver.value = false
  const file = event.dataTransfer?.files[0]
  if (file) void load(file)
}

function beginPan(event: PointerEvent) {
  if (loading.value || applying.value || !event.isPrimary || event.button !== 0) return
  const target = event.currentTarget as HTMLCanvasElement
  target.focus({ preventScroll: true })
  target.setPointerCapture(event.pointerId)
  drag = { id: event.pointerId, x: event.clientX, y: event.clientY, position: { ...position.value } }
}

function pan(event: PointerEvent) {
  if (!loaded.value || !drag || drag.id !== event.pointerId) return
  const rect = (event.currentTarget as HTMLCanvasElement).getBoundingClientRect()
  if (!rect.width || !rect.height) return
  const bounds = cropBounds(loaded.value.element, props.kind, drag.position)
  const dx = (event.clientX - drag.x) * bounds.width / rect.width
  const dy = (event.clientY - drag.y) * bounds.height / rect.height
  position.value = {
    zoom: drag.position.zoom,
    x: bounds.horizontalTravel ? clamp(drag.position.x - dx / bounds.horizontalTravel) : drag.position.x,
    y: bounds.verticalTravel ? clamp(drag.position.y - dy / bounds.verticalTravel) : drag.position.y,
  }
}

function keyPan(event: KeyboardEvent) {
  if (loading.value || applying.value) return
  const directions: Record<string, [number, number]> = {
    ArrowLeft: [1, 0], ArrowRight: [-1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1],
  }
  const direction = directions[event.key]
  if (!direction) return
  event.preventDefault()
  const step = event.shiftKey ? 0.1 : 0.02
  position.value = { ...position.value, x: clamp(position.value.x + direction[0] * step), y: clamp(position.value.y + direction[1] * step) }
}

function close() {
  if (applying.value) return
  dialog.value?.close()
  emit('close')
}

async function apply() {
  if (!canvas.value || !source || loading.value || applying.value || !drawn.value) return
  applying.value = true
  error.value = ''
  try {
    const file = await encodeProfileCrop(canvas.value, props.kind)
    if (alive) emit('apply', file, source, { ...position.value })
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '图片处理失败，请重新选择图片。'
  } finally {
    applying.value = false
  }
}

watch([canvas, loaded, position], () => {
  if (!canvas.value || !loaded.value) return
  try {
    drawProfileCrop(canvas.value, loaded.value.element, props.kind, position.value)
    drawn.value = true
  } catch (cause) {
    drawn.value = false
    error.value = cause instanceof Error ? cause.message : '图片处理失败，请重新选择图片。'
  }
}, { deep: true, flush: 'post' })

onMounted(() => {
  dialog.value?.showModal()
  if (props.source) void load(props.source, props.initialPosition)
})
onBeforeUnmount(() => {
  alive = false
  request?.abort()
  request = undefined
  loaded.value?.release()
  dialog.value?.close()
})
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="profile-image-dialog" :aria-labelledby="titleId" @cancel.prevent="close">
      <div class="crop-header">
        <h2 :id="titleId">{{ t(isAvatar ? '编辑头像' : '编辑主页封面') }}</h2>
        <button type="button" class="crop-close" :aria-label="t('关闭')" :disabled="applying" @click="close">×</button>
      </div>
      <div class="crop-surface" :class="{ 'is-avatar': isAvatar, 'is-drop-target': dragOver, 'has-image': loaded }"
        @dragenter="onDragOver" @dragover="onDragOver" @dragleave="leaveDropArea" @drop.prevent="drop">
        <canvas v-if="loaded" ref="canvas" :width="size.width" :height="size.height" tabindex="0"
          :aria-label="t(isAvatar ? '头像裁剪预览' : '封面裁剪预览')" :aria-describedby="instructionsId"
          @pointerdown="beginPan" @pointermove="pan" @lostpointercapture="drag = undefined" @keydown="keyPan" />
        <template v-else>
          <button type="button" class="button secondary" autofocus @click="fileInput?.click()">{{ t('选择图片') }}</button>
          <p>{{ t(isAvatar ? 'JPG、PNG 或 WebP，最多 5 MB' : '也可将图片拖到这里') }}</p>
        </template>
      </div>
      <input ref="fileInput" type="file" :accept="profileImageAccept" :aria-label="t('选择图片')" hidden :disabled="applying" @change="choose">
      <p v-if="error" class="crop-error" role="alert">{{ t(error) }}</p>
      <p v-if="loading" class="crop-hint" role="status">{{ t('正在处理图片…') }}</p>
      <template v-if="loaded">
        <p :id="instructionsId" class="crop-hint">{{ t('拖动图片或使用方向键，调整裁剪位置。') }}</p>
        <div class="crop-controls">
          <label>{{ t('缩放') }}
            <input v-model.number="position.zoom" type="range" min="1" max="4" step="0.01" :disabled="loading || applying">
          </label>
          <button type="button" class="text-button" :disabled="loading || applying" @click="position = { ...defaultCrop }">{{ t('重置裁剪') }}</button>
        </div>
      </template>
      <div class="crop-actions">
        <button v-if="loaded" type="button" class="text-button crop-replace" :disabled="applying" @click="fileInput?.click()">{{ t('重新选择图片') }}</button>
        <button type="button" class="button secondary" :disabled="applying" @click="close">{{ t('取消') }}</button>
        <button type="button" class="button" :disabled="!drawn || loading || applying" @click="apply">
          {{ t(applying ? '正在处理图片…' : '裁剪并使用') }}
        </button>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.profile-image-dialog {
  box-sizing: border-box; width: min(620px, calc(100% - 24px)); max-height: calc(100dvh - 24px);
  margin: auto; padding: clamp(16px, 4vw, 28px); border: 1px solid #eddee2; border-radius: 20px;
  background: #fffafb; color: var(--ink); box-shadow: 0 22px 74px #25161e30;
}
.profile-image-dialog::backdrop { background: #34232d78; }
.crop-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 15px; }
.crop-header h2 { margin: 0; font-size: clamp(18px, 4vw, 22px); }
.crop-close { width: 36px; height: 36px; padding: 0; border: 1px solid #eddee2; border-radius: 50%; background: #fff; color: var(--ink); font-size: 22px; cursor: pointer; }
.crop-surface {
  display: grid; place-content: center; justify-items: center; min-height: 130px; padding: 12px;
  border: 1px dashed #d9b9bf; border-radius: 13px; background: #f7eef0; text-align: center;
}
.crop-surface.has-image { display: flex; align-items: center; justify-content: center; border-color: transparent; }
.crop-surface.is-drop-target { border-color: var(--accent-text); background: #ffe7df; }
.crop-surface p, .crop-hint, .crop-error { margin: 8px 0 0; color: var(--muted); font-size: 13px; line-height: 1.5; }
.crop-error { color: var(--danger); }
.crop-surface canvas {
  display: block; width: 100%; height: auto; min-width: 0; border-radius: 7px;
  touch-action: none; cursor: grab; background: #fff;
}
.crop-surface canvas:active { cursor: grabbing; }
.crop-surface.is-avatar canvas { width: min(250px, 100%, 40dvh); border-radius: 50%; }
.crop-controls { display: flex; align-items: center; gap: 16px; margin-top: 10px; }
.crop-controls label { flex: 1; display: flex; align-items: center; gap: 12px; font-size: 13px; }
.crop-controls input { flex: 1; min-width: 0; height: 28px; accent-color: var(--accent); }
.crop-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.crop-actions .button, .crop-surface .button {
  min-height: 40px; padding: 8px 14px; border: 1px solid var(--accent-text); border-radius: 24px;
  background: var(--accent-text); color: var(--on-accent); font-size: 13px; font-weight: 650;
}
.profile-image-dialog .button.secondary { border-color: var(--line); background: #fff; color: var(--ink); }
.profile-image-dialog .button:hover:not(:disabled) { background: #a3281d; color: #fff; }
.profile-image-dialog .button.secondary:hover:not(:disabled) { border-color: var(--accent-border); background: var(--accent-soft); color: var(--accent-text); }
.profile-image-dialog .text-button { min-height: 38px; color: var(--accent-text); font-size: 13px; }
.profile-image-dialog button:disabled { opacity: .55; cursor: default; }
.crop-replace { margin-right: auto; }
.profile-image-dialog :is(button, canvas, input):focus-visible { outline: 2px solid var(--accent-text); outline-offset: 3px; }
</style>
