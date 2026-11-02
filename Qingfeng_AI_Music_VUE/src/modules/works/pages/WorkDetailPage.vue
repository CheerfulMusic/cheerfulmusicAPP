<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { loadWork, type Work } from '../../studio'
import { getSystemInfo } from '../../../shared/http/system'
import { listTasks, submitTask, retryTaskArchive, type Revision, type AudioAsset, type GenerationTask } from '../api/detail'
import LyricsPanel from '../components/LyricsPanel.vue'
import AudioPanel from '../components/AudioPanel.vue'
import SharePanel from '../components/SharePanel.vue'
import MusicGenerationDialog from '../components/MusicGenerationDialog.vue'
import { ApiError } from '../../../shared/http/api-client'
const route = useRoute(), work = ref<Work>(), revision = ref<Revision | null>(null), asset = ref<AudioAsset | null>(null), tasks = ref<GenerationTask[]>([]), error = ref(''), loading = ref(false), lyricDirty = ref(false), modelReady = ref(false), submitting = ref(false), requestKey = ref('')
let sequence = 0
const generationOpen = ref(false), trackedTaskId = ref(''), submissionIssue = ref(''), submissionUnknown = ref(false), progressIssue = ref(false)
const inProgress = (task: GenerationTask) => ['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(task.status)
const pendingTask = computed(() => tasks.value.find(inProgress) || tasks.value.find(task => task.status === 'SUBMISSION_UNKNOWN'))
const trackedTask = computed(() => tasks.value.find(task => task.id === trackedTaskId.value))
const generationStatus = computed(() => trackedTask.value?.status || (submissionIssue.value ? submissionUnknown.value ? 'SUBMISSION_UNKNOWN' : 'FAILED' : 'SUBMITTING'))
function showProgress() { if (pendingTask.value) trackedTaskId.value = pendingTask.value.id; generationOpen.value = true }
async function viewGeneratedMusic() {
  generationOpen.value = false; await nextTick()
  const section = document.getElementById('work-audio')
  section?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block:'center' })
  section?.focus({preventScroll:true})
}
const mediaRefresh = ref(0)
const statusLabels: Record<string,string> = {QUEUED:'等待处理',SUBMITTING:'正在提交',RUNNING:'音乐创作中',SAVING:'正在保存音频',SUCCEEDED:'创作完成',FAILED:'处理未完成',SUBMISSION_UNKNOWN:'提交结果待核对'}
let polling = false
async function refreshTasks() {
  if (!work.value || polling || document.visibilityState === 'hidden') return
  const workId = work.value.id; polling = true
  try { const latest = await listTasks(workId); if (work.value?.id !== workId) return; if (latest.some(next => next.status === 'SUCCEEDED' && !tasks.value.some(old => old.id === next.id && old.status === 'SUCCEEDED'))) mediaRefresh.value++; tasks.value = latest; progressIssue.value = false }
  catch (e) { if(work.value?.id === workId) { progressIssue.value = true; error.value = e instanceof Error ? e.message : '进度读取失败，可以刷新重试' } } finally { polling = false }
}
const pollTimer = window.setInterval(() => { if (tasks.value.some(task => ['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(task.status))) void refreshTasks() }, 8000)
document.addEventListener('visibilitychange', refreshTasks)
onBeforeUnmount(() => { window.clearInterval(pollTimer); document.removeEventListener('visibilitychange', refreshTasks); sequence++ })
async function retryArchive(task: GenerationTask) { if (!work.value || submitting.value) return; submitting.value = true; error.value = ''; try { await retryTaskArchive(work.value.id, task.id); await refreshTasks() } catch (e) { error.value = e instanceof Error ? e.message : '重试保存失败' } finally { submitting.value = false } }
async function load() { const turn = ++sequence; generationOpen.value = false; trackedTaskId.value = ''; submissionIssue.value = ''; progressIssue.value = false; submitting.value = false; loading.value = true; error.value = ''; revision.value = null; asset.value = null; work.value = undefined; lyricDirty.value = false; requestKey.value = ''; try { const id = String(route.params.id); const [found, history, system] = await Promise.all([loadWork(id), listTasks(id), getSystemInfo()]); if (turn !== sequence) return; work.value = found; tasks.value = history; trackedTaskId.value = history.find(inProgress)?.id || ''; generationOpen.value = !!trackedTaskId.value; modelReady.value = system.capabilities.musicGeneration } catch (e) { if (turn === sequence) error.value = e instanceof Error ? e.message : '作品读取失败' } finally { if (turn === sequence) loading.value = false } }
watch(() => route.params.id, load, { immediate: true })
watch(() => revision.value?.id, () => { if (!submitting.value && !submissionUnknown.value) requestKey.value = '' })
const permitLeave = () => !lyricDirty.value || window.confirm(t('歌词还有未保存修改，确定离开吗？'))
onBeforeRouteLeave(permitLeave); onBeforeRouteUpdate(permitLeave)
const beforeUnload = (event: BeforeUnloadEvent) => { if (lyricDirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload); onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function generate() {
  if (pendingTask.value) { showProgress(); return }
  if (!work.value || !revision.value?.confirmed || !modelReady.value || submitting.value) return
  const workId = work.value.id, turn = sequence
  submitting.value = true; error.value = ''; submissionIssue.value = ''; submissionUnknown.value = false
  trackedTaskId.value = ''; generationOpen.value = true; requestKey.value ||= crypto.randomUUID()
  try {
    const accepted = await submitTask(workId, revision.value.id, requestKey.value)
    if (turn !== sequence) return
    tasks.value = [accepted, ...tasks.value.filter(task => task.id !== accepted.id)]
    trackedTaskId.value = accepted.id; requestKey.value = ''
  } catch (e) {
    if (turn !== sequence) return
    error.value = e instanceof Error ? e.message : '提交失败，未自动重试'
    submissionIssue.value = error.value
    submissionUnknown.value = !(e instanceof ApiError) || e.status === 0 || e.status === 408 || (e.status >= 500 && !e.code)
  } finally { if (turn === sequence) submitting.value = false }
}

</script>
<template><RouterLink class="detail-back" to="/works">{{ t('← 我的音乐') }}</RouterLink><p v-if="loading" class="notice" role="status">{{ t('正在读取作品…') }}</p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><template v-if="work"><div class="work-detail-top"><div><p class="backend-state">{{ t('专属音乐礼物') }}</p><h1>{{ work.title || t("写给{p0}的一首歌", { p0: work.recipient }) }}</h1><p class="detail-muted">{{ t(work.occasion) }} · {{ t(work.style) }}</p></div><RouterLink class="button secondary" :to="{path:'/create',query:{work:work.id}}">{{ t('修改故事与祝福') }}</RouterLink></div><p class="notice">{{ t('先写下歌词，再让这份心意被听见。') }}{{ modelReady ? t("生成服务已就绪。") : t("音乐生成暂不可用，你仍可导入已有音频并制作礼物。") }}</p><div class="work-detail-layout"><div><LyricsPanel :key="work.id" :work="work" @selected="revision=$event" @dirty="lyricDirty=$event"/><AudioPanel id="work-audio" tabindex="-1" :key="`${work.id}-${mediaRefresh}`" :work="work" @selected="asset=$event"/><SharePanel :work-id="work.id" :revision="revision" :asset="asset"/></div><aside class="work-overview"><img :src="defaultCover()" :alt="t('青风音乐品牌封面')"><h2>{{ t('关于这份心意') }}</h2><p>{{ t('收件人：') }}{{ work.recipient }}</p><p class="private-message">{{ work.message || t("还没有填写祝福。") }}</p><small>{{ t('故事和草稿仅本人可见，分享由你主动创建。') }}</small><div class="model-status"><h3>{{ t('音乐生成') }}</h3><p>{{ modelReady ? t("将使用选定的已确认歌词版本。") : t("音乐生成暂不可用，可以先编辑歌词并导入已有音频。") }}</p><button class="button" :disabled="submitting || (!pendingTask && (!modelReady || !revision?.confirmed))" @click="generate">{{ submitting ? t("正在提交…") : pendingTask ? t("查看生成进度") : modelReady ? t("生成歌曲") : t("歌曲生成暂未开放") }}</button><div v-for="task in tasks" :key="task.id" class="task-entry"><strong>{{ task.title }}</strong><span>{{ t(statusLabels[task.status] || task.status) }}</span><p v-if="task.status === 'SUBMISSION_UNKNOWN'">{{ t('这次创作结果还在确认中，请稍后刷新，避免重复提交。') }}</p><p v-if="task.errorCode === 'QUERY_RETRY'">{{ t('查询暂时失败，稍后自动继续查询。') }}</p><button v-if="task.status === 'FAILED' && task.errorCode === 'AUDIO_ARCHIVE_FAILED'" class="button secondary" :disabled="submitting" @click="retryArchive(task)">{{ t('仅重试保存音频') }}</button><small>{{ formatDate(task.createdAt) }}</small></div><p v-if="!tasks.length">{{ t('当前没有生成任务。') }}</p><button v-else class="button secondary" @click="refreshTasks">{{ t('刷新进度') }}</button></div></aside></div></template><MusicGenerationDialog :open="generationOpen" :status="generationStatus" :title="trackedTask?.title || work?.title || ''" :error="submissionIssue" :sync-issue="progressIssue" @close="generationOpen=false" @view="viewGeneratedMusic"/></template>
