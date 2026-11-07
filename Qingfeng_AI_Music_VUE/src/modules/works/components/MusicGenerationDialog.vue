<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import {generationProblem} from '../model/generation-recovery'

const props = defineProps<{ open: boolean; status: string; title: string; error?: string;errorCode?:string|null; syncIssue?: boolean;createdAt?:string;submitAfter?:string;cancelling?:boolean;cover?:string }>()
const emit = defineEmits<{ close: []; view: [];cancel:[];archive:[] }>()
const problem=computed(()=>generationProblem(props.errorCode)||props.error)
const archiveFailed=computed(()=>props.status==='FAILED'&&props.errorCode==='AUDIO_ARCHIVE_FAILED')
const dialog = ref<HTMLDialogElement>()
const clock=ref(Date.now()),tick=window.setInterval(()=>clock.value=Date.now(),500)
const cancelled=computed(()=>props.status==='CANCELLED')
const elapsed=computed(()=>Math.max(0,Math.floor((clock.value-new Date(props.createdAt||clock.value).getTime())/1000)))
const remaining=computed(()=>Math.min(5,Math.max(0,Math.ceil((new Date(props.submitAfter||0).getTime()-clock.value)/1000))))
const complete = computed(() => props.status === 'SUCCEEDED')
const failed = computed(() => props.status === 'FAILED')
const unknown = computed(() => props.status === 'SUBMISSION_UNKNOWN')
const settled = computed(() => complete.value || failed.value || unknown.value || cancelled.value)
const stage = computed(() => complete.value ? 3 : props.status === 'SAVING' ? 2 : props.status === 'RUNNING' ? 1 : 0)
const heading = computed(() => cancelled.value?'这次创作已撤销':complete.value ? '你的音乐准备好了' : archiveFailed.value?'音乐已完成，正在恢复保存':failed.value ? '这次创作暂未完成' : unknown.value ? '正在核对歌曲结果' : '你的故事，正在成为一首歌')
const labels: Record<string, string> = {CANCELLED:'尚未提交音乐服务，本次歌曲额度已释放。', QUEUED:'即将开始，你仍可以撤销这次创作', SUBMITTING:'正在提交你的音乐创作', RUNNING:'正在为歌词谱曲与演唱', SAVING:'旋律已完成，正在保存音频', SUCCEEDED:'音乐已保存，可以试听和分享', FAILED:'歌词和设置已保留，请查看下方说明选择下一步。', SUBMISSION_UNKNOWN:'提交结果仍待确认，请稍后查看进度，避免重复提交。' }
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
onBeforeUnmount(() => { window.clearInterval(tick);dialog.value?.close(); document.documentElement.classList.remove('music-generation-open') })
</script>
<template>
  <Teleport to="body">
    <dialog ref="dialog" class="music-generation-dialog" aria-labelledby="music-generation-title" aria-describedby="music-generation-description" @cancel.prevent="emit('close')" @click="backdrop">
      <button class="generation-close" type="button" :aria-label="t('关闭生成进度弹窗')" @click="emit('close')">×</button>
      <div class="generation-art" :class="{settled, complete, failed, unknown}" aria-hidden="true"><div class="generation-disc"><img v-if="cover" :src="cover" alt=""><span v-else class="generation-disc-label">♪</span></div><span v-if="complete" class="generation-result-icon">✓</span><span v-else-if="failed || unknown || cancelled" class="generation-result-icon">{{ cancelled?'×':'!' }}</span><div v-else class="generation-wave"><i v-for="n in 5" :key="n" :style="{'--bar':n}"></i></div></div>
      <p class="generation-brand">Cheerful Music</p>
      <h2 id="music-generation-title">{{ t(heading) }}</h2>
      <p class="generation-song">{{ title }}</p>
      <div class="generation-live" role="status" aria-live="polite" aria-atomic="true"><span v-if="!settled" class="generation-live-dot" aria-hidden="true"></span><p id="music-generation-description">{{ syncIssue&&status==='RUNNING'?t('正在核对歌曲结果，暂未确认最新状态'):t(labels[status] || '正在同步音乐创作进度') }}</p></div>
      <p v-if="!settled&&createdAt" class="generation-elapsed">{{ status==='QUEUED'&&remaining>0?t('{seconds} 秒后开始提交',{seconds:remaining}):t('已等待 {seconds} 秒',{seconds:elapsed}) }}</p>
      <ol v-if="!failed && !unknown && !cancelled" class="generation-stages" :aria-label="t('音乐创作阶段')"><li v-for="(label,index) in ['提交创作','谱曲与演唱','保存音频']" :key="label" :class="{current:stage===index,done:stage>index}" :aria-current="stage===index?'step':undefined"><span class="generation-stage-line"></span><span>{{ t(label) }}</span></li></ol>
      <p v-if="problem" class="generation-error" role="alert">{{ t(problem) }}</p>
      <p v-else-if="syncIssue && !settled" class="generation-error" role="status">{{ t('暂时无法更新进度，请稍后查看。关闭弹窗不会重新提交任务。') }}</p>
      <p v-else class="generation-note">{{ complete ? t('把这份旋律，送给你心里的那个人。') : settled ? t('你的故事和歌词仍保存在作品中。') : t('好旋律值得等待，关闭弹窗不会取消任务。') }}</p>
      <button class="generation-primary" type="button" autofocus @click="complete ? emit('view') : emit('close')">{{ complete ? t('试听歌曲') : settled ? t('返回作品') : t('收起，后台继续') }}</button>
      <button v-if="archiveFailed" class="generation-cancel" :disabled="cancelling" @click="emit('archive')">{{ t('仅重试保存音频') }}</button>
      <button v-if="status==='QUEUED'" class="generation-cancel" :disabled="cancelling" @click="emit('cancel')">{{ cancelling?t('正在撤销…'):t('撤销这次生成') }}</button>
      <p v-if="!settled" class="generation-footnote">{{ t('你可以继续浏览，稍后在作品页查看进度。') }}</p>
    </dialog>
  </Teleport>
</template>
