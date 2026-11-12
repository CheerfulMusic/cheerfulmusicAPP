<script setup lang="ts">
import { computed, ref } from 'vue'
import { locale } from '../../../shared/i18n'

const props = defineProps<{ busy: boolean; error: string; disabled: boolean; generationAvailable: boolean | null }>()
const emit = defineEmits<{ close: []; upload: [file: File]; generate: [description: string] }>()
const description = ref('')
const en = computed(() => locale.value === 'en')
function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) emit('upload', file)
}
function generate() {
  const text = description.value.trim()
  if (!text || props.busy || props.disabled || props.generationAvailable !== true) return
  emit('generate', text)
}
</script>

<template>
  <div class="avatar-overlay" @click.self="emit('close')" @keydown.esc.stop="emit('close')">
    <section class="avatar-dialog" role="dialog" aria-modal="true" :aria-label="en ? 'Change avatar' : '设置头像'">
      <header><h2>{{ en ? 'Change avatar' : '设置头像' }}</h2><button type="button" :aria-label="en ? 'Close' : '关闭'" @click="emit('close')">×</button></header>
      <p>{{ en ? 'Choose your own picture, or describe an avatar for the assistant.' : '上传自己的图片，或告诉助手你想要什么样的头像。' }}</p>
      <label class="avatar-upload" :class="{ disabled: busy }">{{ busy ? (en ? 'Uploading…' : '正在上传…') : (en ? 'Upload image' : '上传头像') }}<input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="choose"/></label>
      <p class="avatar-hint">{{ en ? 'JPG or PNG, up to 5 MB.' : '支持 JPG、PNG，大小不超过 5MB。' }}</p>
      <p v-if="error" class="avatar-error" role="alert">{{ error }}</p>
      <label class="avatar-description">{{ en ? 'Describe an AI avatar' : '让 AI 帮我生成头像' }}<textarea v-model="description" maxlength="500" rows="3" :disabled="busy || disabled || generationAvailable !== true" :placeholder="en ? 'For example: a warm watercolor portrait of a cat' : '例如：一只温暖的水彩风小猫'"/></label>
      <p v-if="generationAvailable !== true" class="avatar-hint" role="status">{{ generationAvailable === null ? (en ? 'Checking AI avatar availability…' : '正在检查 AI 头像是否可用…') : (en ? 'AI avatar generation is currently unavailable. You can still upload an image.' : 'AI 头像生成暂不可用，仍可上传头像。') }}</p>
      <button class="avatar-generate" type="button" :disabled="!description.trim() || busy || disabled || generationAvailable !== true" @click="generate">{{ en ? 'Send to assistant' : '发送给助手' }}</button>
    </section>
  </div>
</template>
<style scoped>
.avatar-overlay{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:16px;background:#39231d80}.avatar-dialog{width:min(100%,420px);padding:22px;box-sizing:border-box;border-radius:20px;background:#fffaf7;box-shadow:0 16px 60px #41291f40;color:#503b34}.avatar-dialog header{display:flex;align-items:center;justify-content:space-between}.avatar-dialog h2{margin:0;font-size:20px}.avatar-dialog header button{border:0;background:transparent;color:#755d54;font-size:27px;cursor:pointer}.avatar-dialog p{font-size:13px;line-height:1.6}.avatar-upload,.avatar-generate{display:flex;justify-content:center;align-items:center;width:100%;box-sizing:border-box;border:0;border-radius:12px;background:#ff5b3d;color:white;font-size:14px;font-weight:600;padding:11px;cursor:pointer}.avatar-upload input{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}.avatar-upload:focus-within{outline:3px solid #ffb197;outline-offset:2px}.avatar-upload.disabled,.avatar-generate:disabled{opacity:.5;cursor:not-allowed}.avatar-hint{color:#8b756c}.avatar-error{color:#b33122}.avatar-description{display:grid;gap:8px;font-size:14px;font-weight:600;margin:18px 0 12px}.avatar-description textarea{box-sizing:border-box;width:100%;resize:vertical;padding:10px;border:1px solid #efd3c5;border-radius:10px;background:white;color:#503b34;font:inherit;font-weight:400}
</style>
