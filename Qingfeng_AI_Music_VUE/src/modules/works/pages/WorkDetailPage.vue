<script setup lang="ts">
import { t, formatDate } from '../../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { loadWork, saveWork, type Work } from '../../studio'
import CoverEditor from '../components/CoverEditor.vue'
import {coverUrl,loadCovers,type CoverState} from '../api/covers'
import CreationSettingsSummary from '../components/CreationSettingsSummary.vue'
import MusicSettingsSnapshot from '../components/MusicSettingsSnapshot.vue'
import MusicSettingsPanel from '../components/MusicSettingsPanel.vue'
import type {MusicPreferences} from '../api/music-settings'
import type {EditHint} from '../model/section-edit'
import {useRouter} from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { listTasks, submitTask, preflightGeneration, saveRevision, confirmRevision, retryTaskArchive, cancelTask, getRevision, getUsage, type Allowance, type Revision, type AudioAsset, type GenerationTask } from '../api/detail'
import CreationSteps from '../../../shared/ui/CreationSteps.vue'
import SongResult from '../components/SongResult.vue'
import SongResultSkeleton from '../components/SongResultSkeleton.vue'
import PremiumStudioDialog from '../components/PremiumStudioDialog.vue'
import SectionEditor from '../components/SectionEditor.vue'
import LyricsPanel from '../components/LyricsPanel.vue'
import QuickCreationProgress from '../../studio/components/QuickCreationProgress.vue'
import type {QuickCreation} from '../../studio/api/quick-creation'
import AudioPanel from '../components/AudioPanel.vue'
import SharePanel from '../components/SharePanel.vue'
import { completeSongPublication, getSongPublication, makeSongPrivate, publishSongEverywhere, type SongPublication } from '../../artist/api/artist'
import { accountState, refreshAccountState } from '../../../shared/identity/account-state'
import { logtoState, refreshLogtoState } from '../../../shared/identity/logto-state'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import MusicGenerationDialog from '../components/MusicGenerationDialog.vue'
import { songSettings } from '../model/song-settings'
import { songBaseTitle, songVersionTitle } from '../model/song-title'
import {restoreRemakeSettings} from '../model/remake-settings'
import { ApiError,currentCreatorId,requestJson } from '../../../shared/http/api-client'
import GenerationRecovery from '../components/GenerationRecovery.vue'
import {closeTaskChecking} from '../api/detail'
import {needsGenerationRefresh,rememberGeneration,recoverGeneration,forgetGeneration,uncertainSubmission,generationProblem,previousPublicationScope,rememberPublicationScope,type GenerationPublication,type PendingGeneration,type PublicationScope} from '../model/generation-recovery'
import {creditGap,isCreditShortfall,type Credits} from '../model/credit-shortfall'
const props = defineProps<{ embeddedWorkId?: string }>()
const route = useRoute(), work = ref<Work>(), revision = ref<Revision | null>(null), asset = ref<AudioAsset | null>(null), tasks = ref<GenerationTask[]>([]), error = ref(''), loading = ref(false), lyricDirty = ref(false), modelReady = ref(false), submitting = ref(false), requestKey = ref('')
const lyricsPanel=ref<InstanceType<typeof LyricsPanel>>(), lyricsReady=ref(false), allowance=ref<Allowance>(), giftRevision=ref<Revision|null>(null), lyricBase=ref<Revision|null>(null)
const saveGenerationDialog=ref<HTMLDialogElement>()
const scopeChoice=ref<PublicationScope>('private')
const scopeProfile=ref<Awaited<ReturnType<typeof getSongPublication>>>()
const scopeLoading=ref(false)
const scopeStageName=ref('')
const canChoosePublic=computed(()=>typeof scopeProfile.value?.publicationPending==='boolean' && !scopeProfile.value.song)
const saveLyricsOnConfirmation=computed(()=>!!lyricsPanel.value?.needsSave)

async function openGenerationConfirmation() {
  const id=work.value?.id
  if (!id || scopeLoading.value || saveGenerationDialog.value?.open) return
  const turn=sequence
  const owner=currentCreatorId()
  scopeChoice.value='private'
  scopeStageName.value=''
  scopeProfile.value=undefined
  scopeLoading.value=true
  saveGenerationDialog.value?.showModal()
  try {
    const state=await getSongPublication(id)
    if (turn!==sequence) return
    scopeProfile.value=state
    if (canChoosePublic.value) scopeChoice.value=previousPublicationScope(owner)
  } catch { /* Private generation remains available without a public profile. */ }
  finally { if (turn===sequence) scopeLoading.value=false }
}
function confirmGeneration() {
  if (scopeLoading.value || scopeChoice.value==='public' && (!canChoosePublic.value || !scopeProfile.value?.profileReady && !scopeStageName.value.trim())) return
  const publication:GenerationPublication = scopeChoice.value==='public'
    ? { scope:'public', stageName:scopeStageName.value.trim() || undefined }
    : { scope:'private' }
  saveGenerationDialog.value?.close()
  void generate(true,undefined,publication)
}
const quickBusy=ref(false),quickRevision=ref('')
const mediaState = ref<'loading'|'ready'|'failed'>('loading')
const tasksReady = ref(false), taskReadIssue = ref(false)
const quickChecked = ref(false), quickReadIssue = ref(false)
const quickProgress = ref<InstanceType<typeof QuickCreationProgress>>()
const draftOpened = ref(false)
const entryReady = computed(() => mediaState.value === 'ready' && tasksReady.value && quickChecked.value)
const draftReady = computed(() => entryReady.value && !asset.value && !pendingTask.value && !quickBusy.value && !quickJourney.value)
const entryLoading = computed(() => !asset.value && (loading.value || !!work.value && (
  mediaState.value === 'loading' || mediaState.value === 'ready' && !entryReady.value && !pendingTask.value && !quickBusy.value && !taskReadIssue.value && !quickReadIssue.value
)))
function checkedQuick(ok:boolean) {
  if (ok) quickChecked.value = true
  quickReadIssue.value = !ok
}
function retryAudio(){mediaState.value='loading';mediaRefresh.value++}
const quickFlow=ref<QuickCreation|null>(null)
const quickJourney=computed(()=>route.query.mode!=='custom'&&(route.query.mode==='quick'||!!route.query.quick||!!quickFlow.value))
function resolveQuick(value:QuickCreation|null){quickFlow.value=value}
const dismissedDirection=ref(false)
const musicDirectionChanged=computed(()=>!dismissedDirection.value&&!!lyricBase.value&&lyricBase.value.workVersion!==work.value?.version)
function quickUpdated(value:{revisionId:string|null}){if(value.revisionId)quickRevision.value=value.revisionId;void refreshTasks()}
const lyricRecoverable=ref(false),listenAfterLoad=ref(false),giftStage=ref(false),shared=ref(false),cancelling=ref(false)
const router=useRouter(),covers=ref<CoverState>(),coverOpen=ref(false)
const premiumOpen=ref(false)
watch(()=>asset.value?.id,()=>{premiumOpen.value=false})
const sectionEditor=ref<InstanceType<typeof SectionEditor>>()
const sectionOpen=ref(false),sectionHint=ref<EditHint>()
const remake=ref<{assetId:string;task:GenerationTask;revision:Revision}>()
const remakeError=ref('')
type RemakeInput={expectedVersion:number;preferences:MusicPreferences;lyrics?:string}
function openSection(hint?:EditHint){
  if(asset.value?.origin!=='GENERATED'||sectionOpen.value)return
  sectionHint.value=hint
  sectionOpen.value=true
}
function openMusicAdjustment(){
  if(pendingTask.value||submissionUnknown.value||!generated.value){void generate();return}
  const task=playingSettings.value,snapshot=giftRevision.value
  if(!task||!snapshot||!asset.value||snapshot.id!==task.revisionId)return
  remakeError.value=''
  remake.value={assetId:asset.value.id,task,revision:snapshot}
}
watch(()=>asset.value?.id,()=>{sectionOpen.value=false;sectionHint.value=undefined;remake.value=undefined})
const generated=computed(()=>asset.value?.origin==='GENERATED')
const generationLyricsReady=computed(()=>generated.value?!!giftRevision.value:lyricsReady.value)
const regenerationRevision=ref<Revision|null>(null)
watch([()=>asset.value?.id,()=>work.value?.version],()=>{regenerationRevision.value=null})
watch(generated,value=>{if(value)lyricDirty.value=false})
function sectionAdopted(){giftStage.value=false;shared.value=false;mediaRefresh.value++;void refreshTasks()}
const artwork=computed(()=>coverUrl(work.value?.id||'',covers.value?.currentCoverId))
const publicationCoverReady = ref(false)
const coverNotice = ref('')
function updateCover(value: CoverState) {
  if (!covers.value || value.version >= covers.value.version) covers.value = value
}
function openCover(){coverNotice.value='';coverOpen.value=true}
function coverSaved() {
  coverNotice.value = visibleRelease.value ? '封面已更新并同步。' : '封面已保存。'
}
watch(() => work.value?.id, () => { coverNotice.value = '' })
watch(asset,value=>{if(value&&listenAfterLoad.value){listenAfterLoad.value=false;void viewGeneratedMusic()}})
const currentStep=computed(()=>shared.value?4:giftStage.value?3:asset.value||pendingTask.value||quickJourney.value?2:1)
const playingSettings = computed(() => songSettings(asset.value, tasks.value))
const songVersion = ref<number>()
const songTitle = computed(() => songBaseTitle(work.value?.title, playingSettings.value?.title || revision.value?.title)
  || (work.value?.recipient ? t('写给{recipient}的歌', { recipient: work.value.recipient }) : t('我的原创歌曲')))
const playbackTitle = computed(() => songVersionTitle(songTitle.value, songVersion.value))
const sharedWorkTitle = computed(() => work.value?.title === songTitle.value ? songTitle.value : undefined)
async function renameSong(title: string) {
  const current = work.value
  if (!current || asset.value?.origin !== 'GENERATED') return
  const turn = sequence
  const saved = await saveWork({
    recipient: current.recipient, occasion: current.occasion, story: current.story, message: current.message,
    style: current.style, title, vocalGender: current.vocalGender || 'AUTO',
  }, current)
  if (turn === sequence) work.value = saved
}
const publication = ref<SongPublication>()
const publicationDialog = ref<HTMLDialogElement>()
const publicationState = ref<Awaited<ReturnType<typeof getSongPublication>>>()
const publicationBusy = ref(false)
const stageName = ref('')
let publicationStateRequest = 0
const resumePublication = computed(() => route.query.show === 'publication' || route.query.show === 'discover')
const visibleRelease = computed(() => publicationState.value?.published ? publicationState.value.song : null)
const publicationMatches = computed(() => {
  const song = visibleRelease.value
  return song && song.assetId === asset.value?.id && song.title === songTitle.value.trim()
    && song.coverId === (covers.value?.currentCoverId || null)
})
const publicationLabel = computed(() => {
  if (publicationState.value?.publicationPending) return '正在自动发布到广场'
  if (!visibleRelease.value) return publicationState.value ? '私密' : undefined
  if (!publicationMatches.value) return '旧版本已公开'
  return visibleRelease.value.onDiscover ? '已公开 · 广场和歌手主页' : '已在主页展示'
})
const publicationAction = computed(() => visibleRelease.value || publicationState.value?.publicationPending
  ? '设为私密' : '发布到广场')
const canReplacePublication = computed(() => !!visibleRelease.value && (!visibleRelease.value.onDiscover || !publicationMatches.value))

async function openSongPublication(intent: 'private' | 'publish' | 'prepare') {
  const currentWork = work.value?.id
  const currentAsset = asset.value?.id
  if (!currentWork || !currentAsset || publicationBusy.value) return
  const current=()=>work.value?.id===currentWork && asset.value?.id===currentAsset
  publicationBusy.value=true
  try {
    if (!accountState.value.authenticated && !logtoState.value.authenticated) {
      await Promise.allSettled([refreshAccountState(), refreshLogtoState()])
      if (!current()) return
      if (!accountState.value.authenticated && !logtoState.value.authenticated) {
        showLoginDialog()
        return
      }
    }
    const state=await getSongPublication(currentWork)
    if (!current()) return
    const source={workId:currentWork,assetId:currentAsset,coverId:covers.value?.currentCoverId || null,title:songTitle.value}
    if (intent==='private') {
      const updated=await makeSongPrivate(currentWork)
      if (current()) updatePublicationState(updated)
    } else if (intent==='prepare' || !state.profileReady || state.reopensSongs) {
      updatePublicationState(state)
      publication.value=source
      stageName.value=''
      await nextTick()
      if (current()) publicationDialog.value?.showModal()
    } else {
      const updated=await publishSongEverywhere(source)
      if (current()) updatePublicationState(updated)
    }
  } catch (cause) {
    if (current()) error.value=cause instanceof Error?cause.message:'暂时无法更新歌曲发布状态，请重试。'
  } finally { publicationBusy.value=false }
}

async function confirmPublication() {
  const source=publication.value
  if (!source || publicationBusy.value || !publicationState.value?.profileReady && !stageName.value.trim()) return
  publicationBusy.value=true
  try {
    const updated=await publishSongEverywhere({...source,stageName:stageName.value.trim() || undefined})
    if (work.value?.id!==source.workId) return
    updatePublicationState(updated)
    publicationDialog.value?.close()
  } catch (cause) {
    if (work.value?.id===source.workId) error.value=cause instanceof Error?cause.message:'暂时无法更新歌曲发布状态，请重试。'
  } finally { publicationBusy.value=false }
}

watch([() => work.value?.id, () => asset.value?.id, () => accountState.value.authenticated,
  () => logtoState.value.authenticated, () => covers.value?.version], ([id, audio, google, logto], _, onCleanup) => {
  publicationState.value=undefined
  const request=++publicationStateRequest
  if (!id || !audio || (!google && !logto)) return
  let active=true
  let timer:ReturnType<typeof setTimeout>|undefined
  onCleanup(()=>{active=false;clearTimeout(timer)})
  async function refresh() {
    if (!active || request!==publicationStateRequest || !id) return
    try {
      const state=await getSongPublication(id)
      if (!active || request!==publicationStateRequest) return
      publicationState.value=state
      if (state.publicationPending && !publicationBusy.value) {
        const updated=await completeSongPublication(id)
        if (active && request===publicationStateRequest) publicationState.value=updated
      }
    } catch { /* Poll pending publication again; the scheduled worker can also finish it. */ }
    if (active && request===publicationStateRequest && publicationState.value?.publicationPending)
      timer=setTimeout(refresh,2500)
  }
  void refresh()
}, { immediate: true })
function updatePublicationState(state: Awaited<ReturnType<typeof getSongPublication>>) {
  ++publicationStateRequest
  publicationState.value=state
}
watch([resumePublication, () => asset.value?.id, publicationCoverReady, tasksReady], ([resume, ready]) => {
  if (!resume || !ready || !tasksReady.value || !publicationCoverReady.value) return
  const { show, ...query } = route.query
  if (!publication.value) void openSongPublication('prepare')
  void router.replace({ query, hash: route.hash })
})
const displayedStyle=computed(()=>!asset.value?t(work.value?.style||''):asset.value.origin==='IMPORTED'?t('导入音频'):t(playingSettings.value?.style||'风格记录暂不可用'))
watch([()=>asset.value?.id,()=>covers.value?.currentCoverId,songTitle],()=>{shared.value=false})
let giftSequence=0
watch([asset,revision,tasks],async()=>{
  const turn=++giftSequence;giftRevision.value=null
  if(!asset.value||!work.value)return
  if(asset.value.origin==='IMPORTED'){giftRevision.value=revision.value;return}
  const linked=songSettings(asset.value, tasks.value)
  if(linked)try{const found=await getRevision(work.value.id,linked.revisionId);if(turn===giftSequence)giftRevision.value=found}catch{if(turn===giftSequence)error.value='歌曲对应的歌词暂时无法读取，请刷新后再分享。'}
})
async function updateAllowance(){try{allowance.value=(await getUsage()).music}catch{/* 提交时仍会进行后端额度校验。 */}}
async function sendGift(){
  shared.value=false
  if(asset.value?.origin==='IMPORTED'){try{giftRevision.value=await lyricsPanel.value!.prepareForGeneration()}catch(e){error.value=e instanceof Error?e.message:'请先确认歌词';return}}
  giftStage.value=true
}
let sequence = 0
const generationOpen = ref(false), trackedTaskId = ref(''), submissionIssue = ref(''), submissionUnknown = ref(false), progressIssue = ref(false)
const pendingIntent=ref<PendingGeneration>(),intentOwner=ref(''),requestRecoverable=ref(true),generationNotice=ref(''),inputIssue=ref(false)
// 积分不足是在底部生成按钮处才发现的，提示必须就近显示，不能只留在页面顶部。
const creditIssue=ref(false),credits=ref<Credits>()
let creditEpoch=0
const creditDetail=computed(()=>{
  const gap=creditGap(credits.value)
  return gap?t('这首歌曲生成需要 {cost} 积分，当前可用 {balance} 积分。',{cost:gap.cost,balance:gap.balance}):t('当前积分不足，暂时无法开始谱曲。')
})
async function loadCredits(){
  const epoch=++creditEpoch
  try{const value=await requestJson<Credits>('app/credits');if(epoch===creditEpoch&&value.enabled)credits.value=value}catch{/* 余额读不到时保留通用提示，不猜测数字。 */}
}
function showCreditIssue(){
  creditIssue.value=true;void loadCredits()
  void nextTick(()=>document.getElementById('credit-issue')?.scrollIntoView({block:'center',behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'}))
}
function clearIntent(workId:string){forgetGeneration(intentOwner.value||currentCreatorId(),workId);pendingIntent.value=undefined;requestKey.value='';requestRecoverable.value=true}
function restoreIntent(workId:string,history:GenerationTask[]){
  intentOwner.value=currentCreatorId();pendingIntent.value=recoverGeneration(intentOwner.value,workId)
  if(!pendingIntent.value)return
  const accepted=history.find(task=>task.requestKey===pendingIntent.value?.requestKey)
  if(accepted){rememberPublicationScope(intentOwner.value,pendingIntent.value.publication);trackedTaskId.value=accepted.id;clearIntent(workId);return}
  requestKey.value=pendingIntent.value.requestKey;submissionUnknown.value=true;submissionIssue.value='正在核对上次提交，暂不发起新的音乐创作。'
}
const inProgress = (task: GenerationTask) => ['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(task.status)
const pendingTask = computed(() => tasks.value.find(inProgress) || tasks.value.find(task => task.status === 'SUBMISSION_UNKNOWN'))
watch(draftReady, ready => { if (ready) draftOpened.value = true })
const latestFailure=computed(()=>tasks.value[0]?.status==='FAILED'&&tasks.value[0]?.errorCode!=='CHECKING_CLOSED'?tasks.value[0]:undefined)
const trackedTask = computed(() => tasks.value.find(task => task.id === trackedTaskId.value))
const generationStatus = computed(() => trackedTask.value?.status || (submissionIssue.value ? submissionUnknown.value ? 'SUBMISSION_UNKNOWN' : 'FAILED' : 'SUBMITTING'))
function showProgress() { if (pendingTask.value) trackedTaskId.value = pendingTask.value.id; generationOpen.value = true }
async function viewGeneratedMusic() {
  generationOpen.value = false; await nextTick()
  if(!asset.value)listenAfterLoad.value=true
  const section = document.getElementById('song-result') || document.getElementById('work-audio')
  section?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block:'center' })
  section?.focus({preventScroll:true})
}
const mediaRefresh = ref(0)
const statusLabels: Record<string,string> = {CANCELLED:'已撤销',QUEUED:'等待处理',SUBMITTING:'正在提交',RUNNING:'音乐创作中',SAVING:'正在保存音频',SUCCEEDED:'创作完成',FAILED:'处理未完成',SUBMISSION_UNKNOWN:'提交结果待核对'}
let polling = false
async function refreshTasks() {
  if (!work.value || polling || document.visibilityState === 'hidden') return
  const workId = work.value.id, turn = sequence
  polling = true
  try {
    const latest = await listTasks(workId)
    if (turn !== sequence || work.value?.id !== workId) return
    const firstRead = !tasksReady.value
    const completedNewVersion = latest.some(next => next.status === 'SUCCEEDED' &&
      !tasks.value.some(old => old.id === next.id && old.status === 'SUCCEEDED'))
    if (completedNewVersion) void refreshCover(workId, turn)
    if (completedNewVersion && (!firstRead || mediaState.value === 'ready' && !asset.value)) {
      mediaRefresh.value++
      void updateAllowance()
    }
    tasks.value = latest
    tasksReady.value = true
    taskReadIssue.value = false
    progressIssue.value = false
    if (firstRead) {
      trackedTaskId.value = latest.find(inProgress)?.id || ''
      restoreIntent(workId, latest)
    }
    const recovered = submissionUnknown.value && requestKey.value && latest.find(task => task.requestKey === requestKey.value)
    if (recovered) {
      rememberPublicationScope(intentOwner.value,pendingIntent.value?.publication)
      trackedTaskId.value = recovered.id
      submissionUnknown.value = false
      submissionIssue.value = ''
      clearIntent(workId)
      error.value = ''
    }
  } catch {
    if (turn === sequence) {
      progressIssue.value = true
      taskReadIssue.value = true
    }
  } finally {
    if (turn === sequence) polling = false
  }
}
async function refreshCover(id: string, turn: number) {
  try {
    const value = await loadCovers(id)
    if (turn === sequence && work.value?.id === id) updateCover(value)
  } catch { /* 封面服务暂不可用时保留现有封面。 */ }
}
const coverTimer = window.setInterval(() => {
  const id = work.value?.id
  if (id && document.visibilityState === 'visible' && (covers.value?.automaticPending ||
      covers.value?.edits.some(edit => ['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(edit.status))))
    void refreshCover(id, sequence)
}, 5000)
const pollTimer = window.setInterval(() => { if (work.value&&(!tasksReady.value||taskReadIssue.value||needsGenerationRefresh(tasks.value,submissionUnknown.value))) void refreshTasks() }, 8000)
document.addEventListener('visibilitychange', refreshTasks)
onBeforeUnmount(() => { window.clearInterval(pollTimer);window.clearInterval(coverTimer); document.removeEventListener('visibilitychange', refreshTasks); sequence++ })
async function retryArchive(task: GenerationTask) { if (!work.value || submitting.value) return; submitting.value = true; error.value = ''; try { await retryTaskArchive(work.value.id, task.id); await refreshTasks() } catch (e) { error.value = e instanceof Error ? e.message : '重试保存失败' } finally { submitting.value = false } }
async function closeChecking(task:GenerationTask){
  if(!work.value||submitting.value)return
  const workId=work.value.id;submitting.value=true;error.value=''
  try{await closeTaskChecking(workId,task.id);if(work.value?.id!==workId)return;generationOpen.value=false;await refreshTasks();await updateAllowance()}
  catch(e){if(work.value?.id===workId)error.value=e instanceof Error?e.message:'暂时无法结束等待，请刷新状态。'}
  finally{submitting.value=false}
}
async function load() {
 publicationCoverReady.value=false;scopeLoading.value=false;saveGenerationDialog.value?.close();publicationDialog.value?.close()
 quickBusy.value=false;quickRevision.value='';mediaState.value='loading';tasksReady.value=false;taskReadIssue.value=false;quickChecked.value=false;quickReadIssue.value=false;modelReady.value=false;draftOpened.value=false;listenAfterLoad.value=false;quickFlow.value=null;dismissedDirection.value=false;lyricBase.value=null;lyricsReady.value=false
 const turn=++sequence;polling=false;premiumOpen.value=false;giftStage.value=false;shared.value=false;coverOpen.value=false;covers.value=undefined;publication.value=undefined
 generationOpen.value=false;trackedTaskId.value='';submissionIssue.value='';submissionUnknown.value=false;progressIssue.value=false;submitting.value=false;loading.value=true;error.value='';revision.value=null;asset.value=null;tasks.value=[];giftRevision.value=null;work.value=undefined;lyricDirty.value=false;requestKey.value='';pendingIntent.value=undefined;generationNotice.value='';inputIssue.value=false;creditIssue.value=false;credits.value=undefined
 try{
  const id=String(props.embeddedWorkId || route.params.id),found=await loadWork(id);if(turn!==sequence)return
  work.value=found
  // 先挂载歌曲读取；进度、服务状态和封面均不能阻塞已有音频。
  void refreshTasks();void updateAllowance()
  void getSystemInfo().then(system=>{if(turn===sequence)modelReady.value=system.capabilities.musicGeneration}).catch(()=>{})
  void loadCovers(id).then(value=>{if(turn===sequence)updateCover(value)}).catch(()=>{/* 使用默认封面，保留歌曲试听。 */})
    .finally(()=>{if(turn===sequence)publicationCoverReady.value=true})
 }catch(e){if(turn===sequence)error.value=e instanceof ApiError&&[401,404].includes(e.status)?'这是私人作品编辑页。如果你是收到分享的朋友，请向作者索取“分享歌曲”中复制的收听链接。':e instanceof Error?e.message:'作品读取失败'}finally{if(turn===sequence)loading.value=false}
}
watch(() => props.embeddedWorkId || route.params.id, load, { immediate: true })
watch(() => revision.value?.id, () => { if (!submitting.value && !submissionUnknown.value) requestKey.value = '' })
const permitLeave = () => submissionUnknown.value&&!requestRecoverable.value?window.confirm(t('浏览器无法保留本次请求标识，请先核对生成进度，确定离开吗？')):!lyricDirty.value || lyricRecoverable.value || window.confirm(t('歌词还有未保存修改，确定离开吗？'))
defineExpose({ canClose: permitLeave })
onBeforeRouteLeave(permitLeave); onBeforeRouteUpdate(to=>to.params.id===route.params.id||permitLeave())
const beforeUnload = (event: BeforeUnloadEvent) => { if (lyricDirty.value&&!lyricRecoverable.value||pendingIntent.value&&!requestRecoverable.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload); onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function generate(saveConfirmed=false, adjustment?:RemakeInput, publication?:GenerationPublication) {
  if(quickBusy.value||!tasksReady.value||taskReadIssue.value||!quickChecked.value||quickReadIssue.value||mediaState.value!=='ready')return
  if (pendingTask.value) { showProgress(); return }
  if(submissionUnknown.value){await refreshTasks();generationOpen.value=true;return}
  if (!work.value || (!adjustment&&!generationLyricsReady.value) || !modelReady.value || submitting.value || (!generated.value&&!lyricsPanel.value)) return
  if(adjustment&&(!remake.value||asset.value?.id!==remake.value.assetId||work.value.version!==adjustment.expectedVersion)){
    remakeError.value='草稿已被其他页面修改，请重新载入后再保存'
    return
  }
  const saveChanges=!generated.value&&!!lyricsPanel.value?.needsSave
  if((!asset.value||saveChanges)&&!saveConfirmed){void openGenerationConfirmation();return}
  const workId = work.value.id, turn = sequence, sourceAssetId=generated.value?asset.value!.id:undefined
  const current=()=>turn===sequence&&(!sourceAssetId||asset.value?.id===sourceAssetId)
  submitting.value = true; error.value = ''; remakeError.value=''; submissionIssue.value = ''; submissionUnknown.value = false;inputIssue.value=false;generationNotice.value='';creditIssue.value=false
  trackedTaskId.value = ''
  let posted=false
  try {
    let confirmed:Revision
    if(generated.value){
      const source=adjustment?remake.value?.task:playingSettings.value
      const snapshot=adjustment?remake.value?.revision:giftRevision.value
      if(!source)throw new Error('歌曲对应的原始任务暂时无法读取 请刷新后重试')
      if(!snapshot)throw new Error('歌曲对应的歌词暂时无法读取，请刷新后再试。')
      const restored=await restoreRemakeSettings(work.value!,source,current,adjustment?.preferences)
      if(!restored||!current())return
      work.value=restored;await nextTick()
      if(!current())return
      const text=adjustment?.lyrics?.trim()??snapshot.lyrics
      const cached=regenerationRevision.value
      const title=sharedWorkTitle.value || snapshot.title
      confirmed=cached?.workVersion===restored.version&&cached.lyrics===text&&cached.title===title?cached:snapshot
      if(confirmed.workVersion!==restored.version||confirmed.lyrics!==text||confirmed.title!==title){
        confirmed=await saveRevision(workId,restored.version,title,text,snapshot.id)
        if(!current())return
        regenerationRevision.value=confirmed
      }
      if(!confirmed.confirmed){
        confirmed=await confirmRevision(workId,confirmed.id)
        if(!current())return
        regenerationRevision.value=confirmed
      }
    }else confirmed=await lyricsPanel.value!.prepareForGeneration(saveChanges)
    if(!current())return
    revision.value=confirmed
    const check=await preflightGeneration(workId,confirmed.id)
    if(!current())return
    generationNotice.value=check.notice;giftStage.value=false;shared.value=false
    if(adjustment&&pendingIntent.value?.revisionId!==confirmed.id)requestKey.value=''
    requestKey.value ||= crypto.randomUUID()
    const owner=currentCreatorId()
    intentOwner.value=owner;pendingIntent.value={workId,revisionId:confirmed.id,requestKey:requestKey.value,createdAt:Date.now(),publication}
    requestRecoverable.value=rememberGeneration(intentOwner.value,pendingIntent.value);posted=true
    const accepted = await submitTask(workId, confirmed.id, requestKey.value, publication)
    rememberPublicationScope(owner,publication)
    if (turn !== sequence) return
    tasks.value = [accepted, ...tasks.value.filter(task => task.id !== accepted.id)]
    trackedTaskId.value = accepted.id;clearIntent(workId);remake.value=undefined;generationOpen.value=true
  } catch (e) {
    if (turn !== sequence) return
    // 积分不足是后端明确拒绝：本次没有创建任务，就地提示充值，且不保留待核对意图。
    if(isCreditShortfall(e)){error.value='';submissionUnknown.value=false;submissionIssue.value='';inputIssue.value=false;generationOpen.value=false;clearIntent(workId);remake.value=undefined;showCreditIssue();return}
    error.value = e instanceof Error ? e.message : '提交失败，未自动重试'
    submissionUnknown.value=posted&&uncertainSubmission(e instanceof ApiError?e:{})
    submissionIssue.value=submissionUnknown.value?error.value:''
    inputIssue.value=e instanceof ApiError&&['INVALID_MUSIC_SETTINGS','MUSIC_REQUIREMENTS_TOO_LONG','INVALID_MUSIC_INPUT'].includes(e.code||'')
    generationOpen.value=submissionUnknown.value
    if(submissionUnknown.value)remake.value=undefined
    else if(adjustment)remakeError.value=error.value
    if(!submissionUnknown.value)clearIntent(workId)
  } finally { if (turn === sequence) {submitting.value = false;void updateAllowance()} }
}

async function resumeOriginalRequest(){
 const saved=pendingIntent.value;if(!saved||!work.value||submitting.value)return
 const turn=sequence;submitting.value=true;error.value=''
 try{
  // 重放本站相同幂等请求；不产生新的请求键，不调用新的歌词版本。
  const owner=intentOwner.value
  const accepted=await submitTask(saved.workId,saved.revisionId,saved.requestKey,saved.publication)
  rememberPublicationScope(owner,saved.publication)
  if(turn!==sequence)return
  tasks.value=[accepted,...tasks.value.filter(task=>task.id!==accepted.id)];trackedTaskId.value=accepted.id;submissionUnknown.value=false;submissionIssue.value='';clearIntent(saved.workId);generationOpen.value=true
 }catch(e){if(turn!==sequence)return;if(isCreditShortfall(e)){error.value='';submissionUnknown.value=false;submissionIssue.value='';clearIntent(saved.workId);showCreditIssue();return}error.value=e instanceof Error?e.message:'暂时无法核对原请求';if(!uncertainSubmission(e instanceof ApiError?e:{})){submissionUnknown.value=false;submissionIssue.value='';clearIntent(saved.workId)}}
 finally{if(turn===sequence){submitting.value=false;void updateAllowance()}}
}


async function cancelGeneration(){
 if(!work.value||!trackedTask.value||cancelling.value)return
 cancelling.value=true
 try{const cancelled=await cancelTask(work.value.id,trackedTask.value.id);tasks.value=tasks.value.map(t=>t.id===cancelled.id?cancelled:t);await updateAllowance()}catch(e){submissionIssue.value=e instanceof Error?e.message:'撤销失败，请刷新状态';await refreshTasks()}finally{cancelling.value=false}
}
async function backToStory(){
 if(!work.value||submitting.value)return
 const workId=work.value.id
 try{await lyricsPanel.value?.preserveDraftForNavigation();if(work.value?.id===workId)await router.push({path:'/create',query:{work:workId,mode:'custom',part:'1',returnRevision:lyricBase.value?.id||giftRevision.value?.id||revision.value?.id}})}catch(e){error.value=e instanceof Error?e.message:'请先保存歌词'}
}

</script>
<template>
  <RouterLink v-if="!draftReady" class="detail-back" to="/works">{{ t('← 我的音乐') }}</RouterLink>
  <div v-show="mediaState==='ready'&&(quickBusy||!asset&&quickChecked&&!quickReadIssue)" class="work-entry-progress" :class="{'has-song':!!asset}"><QuickCreationProgress v-if="work" ref="quickProgress" :key="work.id" :work-id="work.id" :title="work.title" :auto-open="false" :result-ready="!!asset" @busy="quickBusy=$event" @checked="checkedQuick" @resolved="resolveQuick" @updated="quickUpdated" @view="viewGeneratedMusic"/></div>
  <SongResultSkeleton v-if="entryLoading"/>
  <p v-if="!asset&&mediaState==='ready'&&quickReadIssue&&!quickBusy" class="notice" role="status">{{ t('进度暂时无法读取 请稍后刷新 原任务不会重复提交') }} <button class="text-button" @click="quickProgress?.refresh()">{{ t('刷新进度') }}</button></p>
  <section v-if="work&&mediaState==='failed'&&!asset" class="notice" role="alert"><p>{{ t('音频暂时无法读取，你的作品仍然保留。') }}</p><button class="button secondary" @click="retryAudio">{{ t('重新读取歌曲') }}</button></section>
  <p v-if="taskReadIssue" class="notice" role="status">{{ t('创作记录暂时无法读取，已有歌曲仍可试听。') }} <button class="text-button" @click="refreshTasks">{{ t('刷新进度') }}</button></p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
  <template v-if="work">
    <header v-if="draftReady" class="lyric-studio-header">
      <button class="description-back-button lyric-studio-back" type="button" :disabled="submitting" @click="backToStory"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5m7-7-7 7 7 7"/></svg>{{ t('返回修改描述') }}</button>
      <CreationSteps :current="currentStep"/>
      <div class="work-detail-top lyrics-page-heading">
        <h1>{{ t('修改歌词') }}</h1><p>{{ t('歌词已生成，你可以继续润色后再生成歌曲') }}</p>
      </div>
    </header>
    <template v-else-if="entryReady&&!asset">
      <CreationSteps :current="currentStep"/>
      <div class="work-detail-top"><div><h1>{{ songTitle }}</h1><p class="detail-muted">{{ displayedStyle }}</p></div></div>
    </template>
    <div v-if="asset&&giftStage" class="story-back-actions"><button class="text-button" @click="giftStage=false;shared=false">{{ t('返回试听与调整') }}</button></div>
    <p v-if="generationNotice" class="notice" role="status">{{ t(generationNotice) }}</p>
    <p v-if="inputIssue" class="notice"><button class="text-button" @click="backToStory">{{ t('调整音乐要求') }}</button> · {{ t('尚未开始音乐生成，你的歌词和设置已保留。') }}</p>
    <section v-if="submissionUnknown&&!pendingTask" class="notice generation-recovery" role="status"><strong>{{ t('正在核对上次提交，暂不发起新的音乐创作。') }}</strong><p>{{ t('你的请求标识已保留。可以刷新核对，或使用原请求继续；不会创建新的生成请求。') }}</p><div class="actions"><button class="button secondary" :disabled="submitting" @click="refreshTasks">{{ t('刷新核对状态') }}</button><button class="button secondary" :disabled="submitting||!pendingIntent" @click="resumeOriginalRequest">{{ t('恢复原请求') }}</button></div></section>
    <p v-if="pendingTask&&!generationOpen&&!quickBusy&&(!quickJourney||asset)" class="notice" role="status">{{ t(generationProblem(pendingTask.errorCode)||'音乐正在后台制作，可以继续浏览，稍后回来查看。') }} <button class="text-button" @click="showProgress">{{ t('查看生成进度') }}</button></p>
    <section v-if="latestFailure&&!pendingTask" class="notice" role="status"><p>{{ t(generationProblem(latestFailure.errorCode)||'音乐服务未完成这次创作，歌词和设置已保留。') }}</p><button v-if="latestFailure.errorCode==='AUDIO_ARCHIVE_FAILED'" class="button secondary" :disabled="submitting" @click="retryArchive(latestFailure)">{{ t('仅重试保存音频') }}</button></section>
    <GenerationRecovery v-if="pendingTask?.status==='SUBMISSION_UNKNOWN'" :task="pendingTask" :busy="submitting" @refresh="refreshTasks" @close="closeChecking(pendingTask)"/>
    <SongResult v-if="asset" :work="work" :asset="asset" :title="songTitle" :version="songVersion" :rename-song="renameSong" :cover="artwork" :pending="!!pendingTask" :checking="submissionUnknown" :busy="submitting||quickBusy||!tasksReady||taskReadIssue||!quickChecked||quickReadIssue||(!pendingTask&&!submissionUnknown&&(!modelReady||!generationLyricsReady))" :lyrics="giftRevision?.lyrics||''" :language="giftRevision?.language" :section-open="sectionOpen" :publication-label="publicationLabel" :publication-action="publicationAction" :publication-busy="publicationBusy || !publicationCoverReady" :can-replace-publication="canReplacePublication" @gift="sendGift" @premium="premiumOpen=true" @publish="openSongPublication(publicationAction === '设为私密' ? 'private' : 'publish')" @replace-publication="openSongPublication('publish')" @regenerate="openMusicAdjustment" @cover="openCover" @edit="openSection"/>
    <p v-if="coverNotice" class="save-info" role="status">{{ t(coverNotice) }}</p>
    <MusicSettingsSnapshot v-if="playingSettings" :work-id="work.id" :task-id="playingSettings.id"/>
    <SectionEditor v-if="asset" ref="sectionEditor" :key="work.id" :work="work" :asset="asset" :title="playbackTitle" :cover="artwork" :tasks="tasks" :open="sectionOpen" :hint="sectionHint" @close="sectionOpen=false" @refresh="refreshTasks" @adopted="sectionAdopted"/>
    <MusicSettingsPanel v-if="remake" :work="work" :remake="remake" :busy="submitting" :submit-error="remakeError" @apply="generate(false,$event)" @close="remake=undefined"/>
    <div v-if="draftReady&&musicDirectionChanged" class="notice" role="status"><p>{{ t('创作设置已更新，可用「AI 重新生成全部歌词」应用新设置。') }}</p><div class="actions"><button type="button" class="button secondary" @click="dismissedDirection=true">{{ t('保留当前歌词') }}</button></div></div>
    <div v-show="!!asset||draftReady" class="work-detail-layout" :class="{'lyric-studio-layout':draftReady}">
      <div>
        <div class="lyrics-editor-fold"><LyricsPanel v-if="!generated&&(asset||draftOpened)" ref="lyricsPanel" :key="work.id+'-'+work.version+'-'+quickRevision" :inert="quickBusy||undefined" :work="work" :show-inline-save="asset?.origin==='IMPORTED'" @selected="revision=$event" @base="lyricBase=$event" @dirty="lyricDirty=$event" @ready="lyricsReady=$event" @recoverable="lyricRecoverable=$event"/>
        </div>
        <section v-if="creditIssue" id="credit-issue" class="credit-issue" role="alert">
          <div class="credit-issue-copy">
            <strong>{{ t('积分不足，暂时不能开始谱曲') }}</strong>
            <p>{{ creditDetail }}</p>
            <p class="save-info">{{ t('歌词和音乐设置都已保留，充值后回来点击生成即可继续。') }}</p>
          </div>
          <div class="credit-issue-actions">
            <RouterLink class="button creation-primary credit-issue-topup" to="/recharge">{{ t('去充值') }}</RouterLink>
            <button type="button" class="text-button" @click="creditIssue=false">{{ t('稍后再说') }}</button>
          </div>
        </section>
        <p v-if="!modelReady&&!asset" class="notice">{{ t('音乐生成暂不可用，可以先编辑并保存歌词，稍后再试。') }}</p>
        <AudioPanel id="work-audio" tabindex="-1" :key="work.id+'-'+mediaRefresh" :work="work" :title="songTitle" :tasks="tasks" :cover="artwork" @selected="asset=$event" @selected-version="songVersion=$event" @load-state="mediaState=$event" @compare="sectionEditor?.compare($event)"/>
        <SharePanel v-if="asset" :open="giftStage" :cover-id="covers?.currentCoverId" @close="giftStage=false" @created="shared=true" :work-id="work.id" :revision="giftRevision" :asset="asset" :work-title="generated ? sharedWorkTitle : undefined"/>
      </div>
      <CreationSettingsSummary v-if="draftReady" :work="work" :revision="lyricBase" :busy="submitting" @edit-music="backToStory"/>
      <aside v-else-if="asset" class="work-overview">
        <h2>{{ work.recipient?t('关于这份心意'):t('创作灵感') }}</h2><p v-if="work.recipient">{{ t('收件人：') }}{{ work.recipient }}</p><p class="private-message">{{ work.message||work.story }}</p><small>{{ t('故事和草稿仅本人可见，分享由你主动创建。') }}</small>
        <div class="model-status"><h3>{{ t('音乐生成') }}</h3><p v-if="allowance">{{ allowance.dailyUnlimited?t('今日歌曲创作不限次数'):t('今日还可创作 {remaining} 次歌曲',{remaining:allowance.dailyRemaining}) }}</p><p v-if="pendingTask">{{ t(statusLabels[pendingTask.status]||pendingTask.status) }}</p>
          <details v-if="tasks.length" class="creation-fold task-history"><summary>{{ t('查看创作记录') }}</summary><div v-for="task in tasks" :key="task.id" class="task-entry"><strong>{{ task.title }}</strong><span>{{ t(statusLabels[task.status]||task.status) }}</span><p v-if="task.status==='SUBMISSION_UNKNOWN'">{{ t('这次创作结果还在确认中，请稍后刷新，避免重复提交。') }}</p><p v-if="task.errorCode==='QUERY_RETRY'">{{ t('查询暂时失败，稍后自动继续查询。') }}</p><button v-if="task.status==='FAILED'&&task.errorCode==='AUDIO_ARCHIVE_FAILED'" class="button secondary" :disabled="submitting" @click="retryArchive(task)">{{ t('仅重试保存音频') }}</button><small>{{ formatDate(task.createdAt) }}</small></div></details>
          <button class="text-button" @click="refreshTasks">{{ t('刷新进度') }}</button>
        </div>
      </aside>
    </div>
    <div v-if="draftReady" class="lyric-studio-actions">
      <button class="button secondary lyric-studio-save" type="button" :disabled="submitting||!lyricsPanel?.canSave" @click="lyricsPanel?.save()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 4h12v16l-6-4-6 4V4Z"/></svg>{{ lyricsPanel?.saving?t('正在保存…'):t('保存歌词') }}</button>
      <button class="button creation-primary" type="button" :disabled="submitting||(!submissionUnknown&&(!modelReady||!lyricsReady))" @click="generate()">{{ submitting?t('正在准备歌曲…'):submissionUnknown?t('核对生成进度'):modelReady?t('生成歌曲'):t('歌曲生成暂未开放') }} <span aria-hidden="true">→</span></button>
      <dialog ref="saveGenerationDialog" class="save-generation-dialog" aria-labelledby="save-generation-heading">
        <h2 id="save-generation-heading">{{ t(saveLyricsOnConfirmation ? '保存当前歌词并生成歌曲？' : '生成歌曲') }}</h2>
        <form @submit.prevent="confirmGeneration">
          <fieldset :disabled="scopeLoading">
            <legend>{{ t('发布范围') }}</legend>
            <label><input v-model="scopeChoice" type="radio" name="generation-scope" value="public" :disabled="!canChoosePublic"> {{ t('发布到广场') }}</label>
            <label><input v-model="scopeChoice" type="radio" name="generation-scope" value="private"> {{ t('私密') }}</label>
          </fieldset>
          <p v-if="scopeLoading" role="status">{{ t('正在读取发布状态…') }}</p>
          <p v-else-if="scopeChoice === 'public'">{{ t('成歌后，访客可在广场和歌手主页试听。') }}</p>
          <p v-else-if="!canChoosePublic">{{ t('成歌后，可以在试听页发布到广场。') }}</p>
          <p v-if="scopeChoice === 'public' && scopeProfile?.reopensSongs">{{ t('公开主页后，此前展示在主页上的歌曲也会恢复可见。') }}</p>
          <label v-if="scopeChoice === 'public' && !scopeProfile?.profileReady" class="publication-name">
            {{ t('艺名') }} <input v-model="scopeStageName" required maxlength="30">
          </label>
          <div class="actions">
            <button type="button" class="button secondary" autofocus @click="saveGenerationDialog?.close()">{{ t('取消') }}</button>
            <button type="submit" class="button primary" :disabled="scopeLoading || scopeChoice === 'public' && !scopeProfile?.profileReady && !scopeStageName.trim()">{{ t(scopeChoice === 'public' ? '生成并发布到广场' : '私密生成') }}</button>
          </div>
        </form>
      </dialog>
    </div>
  </template>
  <CoverEditor v-if="work&&coverOpen" :key="work.id" :work-id="work.id" :state="covers" :published="!!visibleRelease" @saved="coverSaved" @updated="updateCover" @close="coverOpen=false"/>
  <PremiumStudioDialog v-if="work&&asset&&premiumOpen" :open="premiumOpen" :work-id="work.id" :asset="asset" :title="songTitle" :cover="artwork" @close="premiumOpen=false"/>
  <dialog v-if="publication" ref="publicationDialog" class="save-generation-dialog" aria-labelledby="publish-song-heading" @close="publication=undefined">
    <h2 id="publish-song-heading">{{ t('发布到广场') }}</h2>
    <p>{{ t('发布后，访客可在广场和歌手主页试听。') }}</p>
    <p v-if="publicationState?.reopensSongs">{{ t('公开主页后，此前展示在主页上的歌曲也会恢复可见。') }}</p>
    <form @submit.prevent="confirmPublication">
      <label v-if="!publicationState?.profileReady" class="publication-name">{{ t('艺名') }} <input v-model="stageName" required maxlength="30"></label>
      <p v-if="error" role="alert">{{ t(error) }}</p>
      <div class="actions">
        <button type="button" class="button secondary" :disabled="publicationBusy" @click="publicationDialog?.close()">{{ t('取消') }}</button>
        <button type="submit" class="button primary" :disabled="publicationBusy || !publicationState?.profileReady && !stageName.trim()">{{ t('发布到广场') }}</button>
      </div>
    </form>
  </dialog>
  <MusicGenerationDialog :open="generationOpen" :status="generationStatus" :title="trackedTask?.title||work?.title||''" :error="submissionIssue" :error-code="trackedTask?.errorCode" :sync-issue="progressIssue||trackedTask?.errorCode==='QUERY_RETRY'" :created-at="trackedTask?.createdAt" :submit-after="trackedTask?.nextAttemptAt" :cancelling="cancelling||submitting" :cover="artwork" @cancel="cancelGeneration" @archive="trackedTask&&retryArchive(trackedTask)" @close="generationOpen=false" @view="viewGeneratedMusic"/>
</template>
<style scoped>
.lyric-studio-header { position:relative; margin-bottom:30px; }
:global(.consumer-shell .workspace:has(.lyric-studio-header)) { max-width:1540px; padding-top:24px; }
.lyrics-page-heading { display:grid; justify-content:center; gap:4px; text-align:center; margin:0; }
.work-detail-top.lyrics-page-heading h1 { margin:0; font-size:clamp(30px,2.5vw,42px); color:#211d1d; line-height:1.25; }
.lyrics-page-heading p { margin:0; color:#62595b; font-size:clamp(14px,1.15vw,18px); }
.lyric-studio-header :deep(.creation-steps) { margin-bottom:22px; }
.lyric-studio-header :deep(.creation-steps li.done > span) { background:#d7cccf; }
.lyric-studio-back { position:absolute; top:5px; left:0; display:inline-flex; align-items:center; gap:8px; }
.lyric-studio-back svg { width:16px; height:16px; fill:none; stroke:currentColor; stroke-width:1.5; }
.lyric-studio-layout { grid-template-columns:minmax(0,2.22fr) minmax(255px,1fr); align-items:stretch; gap:clamp(18px,1.7vw,28px); }
.lyric-studio-layout .lyrics-editor-fold { border:0; margin:0; }
.lyric-studio-layout .lyrics-editor-fold :deep(.detail-panel) { margin:0; }
.lyric-studio-actions { display:flex; justify-content:flex-end; gap:16px; margin-top:16px; }
.lyric-studio-actions > .button { min-height:52px; border-radius:999px; font-size:16px; }
.lyric-studio-actions > .creation-primary { min-width:clamp(165px,13vw,216px); }
.lyric-studio-actions > .lyric-studio-save { min-width:clamp(140px,11.5vw,190px); gap:8px; }
.lyric-studio-save svg { width:18px; height:18px; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linejoin:round; }
.save-generation-dialog { width:min(420px,calc(100% - 32px)); padding:22px; border:1px solid var(--line); border-radius:18px; color:var(--ink); background:#fffafa; box-shadow:0 22px 66px #39212638; }
.save-generation-dialog::backdrop { background:#39272e66; }
.save-generation-dialog h2 { margin:0 0 8px; font-size:20px; }
.save-generation-dialog p { margin:0 0 16px; color:var(--muted); }
.save-generation-dialog .actions { justify-content:flex-end; gap:10px; margin:0; }
.save-generation-dialog .actions button { min-width:0; width:auto; }
.save-generation-dialog fieldset { border:0; padding:0; margin:0 0 12px; display:flex; gap:16px; flex-wrap:wrap; }
.save-generation-dialog legend { font-weight:700; margin-bottom:8px; }
.save-generation-dialog fieldset label { display:flex; align-items:center; gap:5px; }
.publication-name { display:grid; gap:6px; margin-bottom:14px; }
.publication-name input { width:100%; padding:10px; border:1px solid var(--line); border-radius:8px; font:inherit; }
@media(max-width:760px) {
 .lyric-studio-header { margin-bottom:20px; }
 .lyric-studio-back { position:static; }
 .lyric-studio-header :deep(.creation-steps) { margin-bottom:12px; }
 .lyric-studio-layout { grid-template-columns:minmax(0,1fr); gap:14px; padding-bottom:0; }
 .lyric-studio-actions { position:sticky; bottom:0; z-index:2; gap:8px; margin-top:10px; padding:10px 0 calc(8px + env(safe-area-inset-bottom)); background:var(--page,#fcf6f6); }
 .lyric-studio-actions > .button { width:auto; min-width:0; min-height:46px; font-size:14px; flex:1 1 auto; }
 :global(.has-preview .lyric-studio-actions) { bottom:calc(86px + env(safe-area-inset-bottom)); }
 .lyric-studio-layout:has(:deep(textarea:focus)) + .lyric-studio-actions { position:static; }
}
.work-entry-progress.has-song :deep(.quick-progress){align-items:center;gap:12px;margin:12px 0;padding:12px 18px;border-radius:14px}
.work-entry-progress.has-song :deep(.quick-progress-icon),.work-entry-progress.has-song :deep(.quick-progress-copy>p:not([role="alert"])){display:none}
.work-entry-progress.has-song :deep(.quick-progress-copy){display:flex;align-items:center;flex-wrap:wrap;gap:10px 20px}
.work-entry-progress.has-song :deep(.quick-progress strong){font-size:15px}
.work-entry-progress.has-song :deep(.actions){margin:0}

.credit-issue { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:14px 22px; margin:26px 0 0; padding:16px 20px; border:1px solid var(--warning-border); border-radius:16px; background:var(--warning-soft); scroll-margin:130px; }
.credit-issue-copy { min-width:0; }
.credit-issue-copy strong { display:block; color:var(--ink); font-size:14px; font-weight:650; line-height:1.6; }
.credit-issue-copy p { margin:6px 0 0; color:var(--muted); font-size:12px; line-height:1.8; overflow-wrap:anywhere; }
.credit-issue-actions { display:flex; align-items:center; gap:10px; flex:none; }
.credit-issue .credit-issue-topup { display:inline-flex; align-items:center; justify-content:center; min-width:0; min-height:42px; padding:10px 22px; font-size:12px; font-weight:600; white-space:nowrap; text-decoration:none; }
@media(max-width:600px) {
 .credit-issue { margin-top:20px; padding:14px 16px; gap:12px; }
 .credit-issue-actions { width:100%; }
 .credit-issue .credit-issue-topup { flex:1; }
}
.story-back-actions .description-back-button { display:inline-flex; align-items:center; justify-content:center; gap:9px; min-height:48px; padding:11px 20px; border:1px solid #ec9e87; border-radius:24px; background:#fff0e8; color:#b93422; font:inherit; font-size:17px; font-weight:600; line-height:1.5; cursor:pointer; text-decoration:none; }
.description-back-button svg { width:20px; height:20px; flex:none; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
.story-back-actions .description-back-button:hover { background:#ffe4d7; border-color:#df6950; }
.description-back-button:focus-visible { outline:2px solid #f93822; outline-offset:3px; }
@media(max-width:600px) { .story-back-actions .description-back-button { font-size:16px; padding-inline:17px; } }
</style>
