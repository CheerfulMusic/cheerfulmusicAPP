<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'

const props = defineProps<{ open: boolean; status: string; title: string; error?: string; syncIssue?: boolean }>()
const emit = defineEmits<{ close: []; view: [] }>()
const dialog = ref<HTMLDialogElement>()
const complete = computed(() => props.status === 'SUCCEEDED')
const failed = computed(() => props.status === 'FAILED')
const unknown = computed(() => props.status === 'SUBMISSION_UNKNOWN')
const settled = computed(() => complete.value || failed.value || unknown.value)
const stage = computed(() => complete.value ? 3 : props.status === 'SAVING' ? 2 : props.status === 'RUNNING' ? 1 : 0)
const heading = computed(() => complete.value ? '你的音乐准备好了' : failed.value ? '这次创作暂未完成' : unknown.value ? '正在核对提交结果' : '你的故事，正在成为一首歌')
const labels: Record<string, string> = { QUEUED:'已排队，等待开始创作', SUBMITTING:'正在提交你的音乐创作', RUNNING:'正在为歌词谱曲与演唱', SAVING:'旋律已完成，正在保存音频', SUCCEEDED:'音乐已保存，可以试听和分享', FAILED:'请返回作品页查看提示，稍后再试。', SUBMISSION_UNKNOWN:'提交结果仍待确认，请稍后查看进度，避免重复提交。' }
function syncOpen() {
  if (!dialog.value) return
  if (props.open && !dialog.value.open) dialog.value.showModal()
  else if (!props.open && dialog.value.open) dialog.value.close()
  document.documentElement.classList.toggle('music-generation-open', props.open)
}
function backdrop(event: MouseEvent) {
  if (event.target !== dialog.value) return
  const bounds = dialog.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) emit('close')
}
watch(() => props.open, syncOpen, { flush: 'post' })
onMounted(syncOpen)
onBeforeUnmount(() => { dialog.value?.close(); document.documentElement.classList.remove('music-generation-open') })
</script>
<template>
  <Teleport to="body">
    <dialog ref="dialog" class="music-generation-dialog" aria-labelledby="music-generation-title" aria-describedby="music-generation-description" @cancel.prevent="emit('close')" @click="backdrop">
      <button class="generation-close" type="button" :aria-label="t('关闭生成进度弹窗')" @click="emit('close')">×</button>
      <div class="generation-art" :class="{settled, complete, failed, unknown}" aria-hidden="true"><div class="generation-disc"><span class="generation-disc-label">♪</span></div><span v-if="complete" class="generation-result-icon">✓</span><span v-else-if="failed || unknown" class="generation-result-icon">!</span><div v-else class="generation-wave"><i v-for="n in 5" :key="n" :style="{'--bar':n}"></i></div></div>
      <p class="generation-brand">Cheerful Music</p>
      <h2 id="music-generation-title">{{ t(heading) }}</h2>
      <p class="generation-song">{{ title }}</p>
      <div class="generation-live" role="status" aria-live="polite" aria-atomic="true"><span v-if="!settled" class="generation-live-dot" aria-hidden="true"></span><p id="music-generation-description">{{ t(labels[status] || '正在同步音乐创作进度') }}</p></div>
      <ol v-if="!failed && !unknown" class="generation-stages" :aria-label="t('音乐创作阶段')"><li v-for="(label,index) in ['提交创作','谱曲与演唱','保存音频']" :key="label" :class="{current:stage===index,done:stage>index}" :aria-current="stage===index?'step':undefined"><span class="generation-stage-line"></span><span>{{ t(label) }}</span></li></ol>
      <p v-if="error" class="generation-error" role="alert">{{ t(error) }}</p>
      <p v-else-if="syncIssue && !settled" class="generation-error" role="status">{{ t('暂时无法更新进度，请稍后查看。关闭弹窗不会重新提交任务。') }}</p>
      <p v-else class="generation-note">{{ complete ? t('把这份旋律，送给你心里的那个人。') : settled ? t('你的故事和歌词仍保存在作品中。') : t('好旋律值得等待，关闭弹窗不会取消任务。') }}</p>
      <button class="generation-primary" type="button" autofocus @click="complete ? emit('view') : emit('close')">{{ complete ? t('查看作品并试听') : settled ? t('返回作品') : t('后台继续') }}</button>
      <p v-if="!settled" class="generation-footnote">{{ t('你可以继续浏览，稍后在作品页查看进度。') }}</p>
    </dialog>
  </Teleport>
</template>
