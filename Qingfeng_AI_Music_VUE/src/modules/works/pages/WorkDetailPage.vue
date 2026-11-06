<script setup lang="ts">
import { t, formatDate } from '../../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { loadWork, type Work } from '../../studio'
import CoverEditor from '../components/CoverEditor.vue'
import {coverUrl,loadCovers,type CoverState} from '../api/covers'
import {usePlayer} from '../../../shared/player/store'
import MusicSettingsPanel from '../components/MusicSettingsPanel.vue'
import SoundDirectionEntry from '../components/SoundDirectionEntry.vue'
import MusicSettingsSnapshot from '../components/MusicSettingsSnapshot.vue'
import {saveMusicSettings,type MusicPreferences} from '../api/music-settings'
import {useRouter} from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { listTasks, submitTask, retryTaskArchive, cancelTask, getRevision, getUsage, type Allowance, type Revision, type AudioAsset, type GenerationTask } from '../api/detail'
import CreationSteps from '../../../shared/ui/CreationSteps.vue'
import SongResult from '../components/SongResult.vue'
import PremiumStudioDialog from '../components/PremiumStudioDialog.vue'
import SectionEditor from '../components/SectionEditor.vue'
import type {EditHint} from '../model/section-edit'
import LyricsPanel from '../components/LyricsPanel.vue'
import AudioPanel from '../components/AudioPanel.vue'
import SharePanel from '../components/SharePanel.vue'
import MusicGenerationDialog from '../components/MusicGenerationDialog.vue'
import { songSettings } from '../model/song-settings'
import { vocalGenderLabel } from '../../studio/model/vocals'
import { ApiError } from '../../../shared/http/api-client'
import GenerationRecovery from '../components/GenerationRecovery.vue'
import {closeTaskChecking} from '../api/detail'
import {needsGenerationRefresh} from '../model/generation-recovery'
const route = useRoute(), work = ref<Work>(), revision = ref<Revision | null>(null), asset = ref<AudioAsset | null>(null), tasks = ref<GenerationTask[]>([]), error = ref(''), loading = ref(false), lyricDirty = ref(false), modelReady = ref(false), submitting = ref(false), requestKey = ref('')
const lyricsPanel=ref<InstanceType<typeof LyricsPanel>>(), lyricsReady=ref(false), allowance=ref<Allowance>(), giftRevision=ref<Revision|null>(null)
const lyricRecoverable=ref(false),listenAfterLoad=ref(false),giftStage=ref(false),shared=ref(false),settingsOpen=ref(false),settingsBusy=ref(false),cancelling=ref(false)
const router=useRouter(),player=usePlayer(),covers=ref<CoverState>(),coverOpen=ref(false)
const premiumOpen=ref(false)
watch(()=>asset.value?.id,()=>{premiumOpen.value=false})
const musicSettingsPanel=ref<InstanceType<typeof MusicSettingsPanel>>()
const sectionOpen=ref(false),sectionHint=ref<EditHint>(),sectionEditor=ref<InstanceType<typeof SectionEditor>>()
function editSection(hint?:EditHint){if(player.current?.id===asset.value?.id&&(player.playing||player.starting))void player.toggle(player.current!);sectionHint.value=hint;sectionOpen.value=true;generationOpen.value=false}
function sectionAdopted(){sectionOpen.value=false;giftStage.value=false;shared.value=false;mediaRefresh.value++;void refreshTasks()}
const artwork=computed(()=>coverUrl(work.value?.id||'',covers.value?.currentCoverId))
function updateCover(value:CoverState){covers.value=value;if(player.current?.audio.includes(`/app/works/${work.value?.id}/audio/`))player.updateCover(player.current.id,artwork.value)}
function openCover(){coverOpen.value=true;void nextTick(()=>document.getElementById('cover-editor')?.scrollIntoView({block:'start',behavior:'smooth'}))}
watch(asset,value=>{if(value&&listenAfterLoad.value){listenAfterLoad.value=false;void viewGeneratedMusic()}})
const currentStep=computed(()=>shared.value?4:giftStage.value?3:asset.value||pendingTask.value?2:1)
const songTitle=computed(()=>tasks.value.find(task=>task.assetId===asset.value?.id)?.title||work.value?.title||revision.value?.title||t('我的音乐'))
const playingSettings=computed(()=>songSettings(asset.value,tasks.value))
const displayedStyle=computed(()=>!asset.value?t(work.value?.style||''):asset.value.origin==='IMPORTED'?t('导入音频'):t(playingSettings.value?.style||'风格记录暂不可用'))
const nextSettings=computed(()=>t('生成将使用：{style} · {voice}',{style:t(work.value?.style||''),voice:t(vocalGenderLabel(work.value?.vocalGender))}))
watch([()=>asset.value?.id,()=>covers.value?.currentCoverId],()=>{shared.value=false})
let giftSequence=0
watch([asset,revision,tasks],async()=>{
  const turn=++giftSequence;giftRevision.value=null
  if(!asset.value||!work.value)return
  if(asset.value.origin==='IMPORTED'){giftRevision.value=revision.value;return}
  const linked=tasks.value.find(task=>task.assetId===asset.value!.id)
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
const inProgress = (task: GenerationTask) => ['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(task.status)
const pendingTask = computed(() => tasks.value.find(inProgress) || tasks.value.find(task => task.status === 'SUBMISSION_UNKNOWN'))
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
let settingsOperation=0
let polling = false
async function refreshTasks() {
  if (!work.value || polling || document.visibilityState === 'hidden') return
  const workId = work.value.id; polling = true
  try { const latest = await listTasks(workId); if (work.value?.id !== workId) return; if (latest.some(next => next.status === 'SUCCEEDED' && !tasks.value.some(old => old.id === next.id && old.status === 'SUCCEEDED'))) {mediaRefresh.value++;void updateAllowance()} tasks.value = latest; progressIssue.value = false
    const recovered=submissionUnknown.value&&requestKey.value?latest.find(task=>task.requestKey===requestKey.value):undefined
    if(recovered){trackedTaskId.value=recovered.id;submissionUnknown.value=false;submissionIssue.value='';requestKey.value='';error.value=''}
  }
  catch (e) { if(work.value?.id === workId) { progressIssue.value = true; error.value = e instanceof Error ? e.message : '进度读取失败，可以刷新重试' } } finally { polling = false }
}
const coverTimer=window.setInterval(()=>{const id=work.value?.id;if(id&&document.visibilityState==='visible'&&covers.value?.edits.some(e=>['QUEUED','SUBMITTING','RUNNING','SAVING'].includes(e.status)))void loadCovers(id).then(v=>{if(work.value?.id===id)updateCover(v)}).catch(()=>{})},5000)
const pollTimer = window.setInterval(() => { if (needsGenerationRefresh(tasks.value,submissionUnknown.value)) void refreshTasks() }, 8000)
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
async function load() { const turn = ++sequence;settingsOperation++;settingsBusy.value=false;premiumOpen.value=false;sectionOpen.value=false;giftStage.value=false;shared.value=false;settingsOpen.value=false;coverOpen.value=false;covers.value=undefined; generationOpen.value = false; trackedTaskId.value = ''; submissionIssue.value = ''; submissionUnknown.value=false; progressIssue.value = false; submitting.value = false; loading.value = true; error.value = ''; revision.value = null; asset.value = null; tasks.value=[];giftRevision.value=null;work.value = undefined; lyricDirty.value = false; requestKey.value = ''; try { const id = String(route.params.id); const [found, history, system] = await Promise.all([loadWork(id), listTasks(id), getSystemInfo()]); if (turn !== sequence) return; work.value = found; tasks.value = history;void loadCovers(id).then(value=>{if(turn===sequence)updateCover(value)}).catch(e=>{if(turn===sequence)error.value=e instanceof Error?e.message:'封面读取失败'}); trackedTaskId.value = history.find(inProgress)?.id || ''; generationOpen.value = !!trackedTaskId.value; modelReady.value = system.capabilities.musicGeneration;void updateAllowance() } catch (e) { if (turn === sequence) error.value = e instanceof ApiError && [401,404].includes(e.status) ? '这是私人作品编辑页。如果你是收到分享的朋友，请向作者索取“分享歌曲”中复制的收听链接。' : e instanceof Error ? e.message : '作品读取失败' } finally { if (turn === sequence) loading.value = false } }
watch(() => route.params.id, load, { immediate: true })
watch(() => revision.value?.id, () => { if (!submitting.value && !submissionUnknown.value) requestKey.value = '' })
const permitLeave = () => !lyricDirty.value || lyricRecoverable.value || window.confirm(t('歌词还有未保存修改，确定离开吗？'))
onBeforeRouteLeave(permitLeave); onBeforeRouteUpdate(to=>to.params.id===route.params.id||permitLeave())
const beforeUnload = (event: BeforeUnloadEvent) => { if (lyricDirty.value&&!lyricRecoverable.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload); onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function generate() {
  if (pendingTask.value) { showProgress(); return }
  if(submissionUnknown.value){await refreshTasks();generationOpen.value=true;return}
  if (!work.value || !lyricsReady.value || !modelReady.value || submitting.value || !lyricsPanel.value) return
  const workId = work.value.id, turn = sequence
  submitting.value = true; error.value = ''; submissionIssue.value = ''; submissionUnknown.value = false
  trackedTaskId.value = ''
  try {
    const confirmed=await lyricsPanel.value.prepareForGeneration()
    if(turn!==sequence)return
    revision.value=confirmed;giftStage.value=false;shared.value=false;generationOpen.value=true;requestKey.value ||= crypto.randomUUID()
    const accepted = await submitTask(workId, confirmed.id, requestKey.value)
    if (turn !== sequence) return
    tasks.value = [accepted, ...tasks.value.filter(task => task.id !== accepted.id)]
    trackedTaskId.value = accepted.id; requestKey.value = ''
  } catch (e) {
    if (turn !== sequence) return
    error.value = e instanceof Error ? e.message : '提交失败，未自动重试'
    submissionIssue.value = error.value
    submissionUnknown.value = !(e instanceof ApiError) || e.status === 0 || e.status === 408 || (e.status >= 500 && !e.code)
  } finally { if (turn === sequence) {submitting.value = false;void updateAllowance()} }
}


async function cancelGeneration(){
 if(!work.value||!trackedTask.value||cancelling.value)return
 cancelling.value=true
 try{const cancelled=await cancelTask(work.value.id,trackedTask.value.id);tasks.value=tasks.value.map(t=>t.id===cancelled.id?cancelled:t);await updateAllowance()}catch(e){submissionIssue.value=e instanceof Error?e.message:'撤销失败，请刷新状态';await refreshTasks()}finally{cancelling.value=false}
}
async function backToStory(){
 try{await lyricsPanel.value?.preserveDraftForNavigation();await router.push({path:'/create',query:{work:work.value!.id,part:'1',returnRevision:revision.value?.id}})}catch(e){error.value=e instanceof Error?e.message:'请先保存歌词'}
}
async function applyMusicSettings(value:{expectedVersion:number;preferences:MusicPreferences}){
 if(!work.value||settingsBusy.value)return
 const workId=work.value.id,turn=sequence,operation=++settingsOperation,panel=musicSettingsPanel.value,lyrics=lyricsPanel.value
 const input=JSON.parse(JSON.stringify(value)) as typeof value
 const current=()=>turn===sequence&&operation===settingsOperation&&work.value?.id===workId&&musicSettingsPanel.value===panel
 settingsBusy.value=true;error.value=''
 try{
  await lyrics?.preserveDraftForNavigation();if(!current())return
  const saved=await saveMusicSettings(workId,input.expectedVersion,input.preferences);if(!current())return
  panel?.markSaved();work.value=saved.work;settingsOpen.value=false;void updateAllowance()
 }catch(e){if(current())error.value=e instanceof Error?e.message:'设置保存失败，请重试'}finally{if(turn===sequence&&operation===settingsOperation)settingsBusy.value=false}
}
function adjustMusic(){giftStage.value=false;shared.value=false;settingsOpen.value=true;void nextTick(()=>document.getElementById('music-settings')?.scrollIntoView({block:'center',behavior:'smooth'}))}

</script>
<template>
  <RouterLink class="detail-back" to="/works">{{ t('← 我的音乐') }}</RouterLink>
  <p v-if="loading" class="notice" role="status">{{ t('正在读取作品…') }}</p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
  <template v-if="work">
    <CreationSteps :current="currentStep"/>
    <div class="work-detail-top"><div><h1>{{ asset?songTitle:work.title||(work.recipient?t('写给{recipient}的歌',{recipient:work.recipient}):t('我的原创歌曲')) }}</h1><p class="detail-muted"><template v-if="work.recipient">{{ t('送给 {recipient}',{recipient:work.recipient}) }} · </template> {{ displayedStyle }}<template v-if="playingSettings?.vocalGender"> · {{ t(vocalGenderLabel(playingSettings.vocalGender)) }}</template></p></div></div>
    <div class="story-back-actions"><button class="description-back-button" type="button" @click="backToStory"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 12H5m7-7-7 7 7 7"/></svg>{{ t('返回修改描述') }}</button><button v-if="giftStage" class="text-button" @click="giftStage=false;shared=false">{{ t('返回试听与调整') }}</button></div>
    <GenerationRecovery v-if="pendingTask?.status==='SUBMISSION_UNKNOWN'" :task="pendingTask" :busy="submitting" @refresh="refreshTasks" @close="closeChecking(pendingTask)"/>
    <SongResult v-if="asset" :work="work" :asset="asset" :title="songTitle" :cover="artwork" :pending="!!pendingTask" :busy="submitting||settingsBusy||(!pendingTask&&!submissionUnknown&&(!modelReady||!lyricsReady))" :lyrics="giftRevision?.lyrics||''" @gift="sendGift" @regenerate="generate" @cover="openCover" @edit="editSection"/>
    <MusicSettingsSnapshot v-if="asset&&tasks.find(task=>task.assetId===asset?.id)" :work-id="work.id" :task-id="tasks.find(task=>task.assetId===asset?.id)!.id"/>
    <SectionEditor v-if="asset" ref="sectionEditor" :key="work.id" :work="work" :asset="asset" :title="songTitle" :cover="artwork" :tasks="tasks" :open="sectionOpen" :hint="sectionHint" @close="sectionOpen=false" @refresh="refreshTasks" @adopted="sectionAdopted"/>
    <RouterLink v-if="asset" class="recovery-reminder" to="/account">{{ t('第一首歌值得留住。保存找回码，换设备也能找到它。') }}</RouterLink>
    <CoverEditor v-if="work&&(coverOpen)" :key="work.id" :work-id="work.id" :state="covers" @updated="updateCover" @close="coverOpen=false"/>
    <MusicSettingsPanel ref="musicSettingsPanel" v-if="work&&settingsOpen" :work="work" :busy="settingsBusy" :initial="!asset" @apply="applyMusicSettings" @close="settingsOpen=false"/>
    <div class="work-detail-layout">
      <div>
        <details class="lyrics-editor-fold" :open="!asset"><summary v-show="asset">{{ t('查看或修改歌词') }}</summary><LyricsPanel ref="lyricsPanel" :key="work.id+'-'+work.version" :work="work" @selected="revision=$event" @dirty="lyricDirty=$event" @ready="lyricsReady=$event" @recoverable="lyricRecoverable=$event"/>
          <section v-if="asset" class="regeneration-music-settings" :aria-label="t('下次生成的音乐设置')">
            <div><strong>{{ t('下次生成的音乐设置') }}</strong><p class="save-info next-song-settings">{{ nextSettings }}</p><p class="save-info">{{ t('修改风格只影响下次生成，已有歌曲保持原样。') }}</p></div>
            <button class="button secondary" type="button" :disabled="submitting||!!pendingTask||submissionUnknown||settingsBusy" @click="adjustMusic">{{ t('调整歌曲') }}</button>
          </section>
          <div v-if="asset" class="actions"><button class="button secondary" :disabled="submitting||(!pendingTask&&!submissionUnknown&&(!modelReady||!lyricsReady))" @click="generate">{{ submissionUnknown?t('核对生成进度'):pendingTask?t('查看生成进度'):t('用这版歌词重新生成歌曲') }}</button><span class="save-info">{{ t('重新生成歌曲会使用一次歌曲创作额度。') }}</span></div>
        </details>
        <SoundDirectionEntry v-if="!asset" :style-label="t(work.style)" :voice-label="t(vocalGenderLabel(work.vocalGender))" :disabled="submitting||!!pendingTask||submissionUnknown||settingsBusy" @choose="adjustMusic"/>
        <div class="creation-action-bar work-primary-bar" :class="{'context-editor-open':coverOpen||settingsOpen}">
          <template v-if="asset"><button class="button creation-primary" @click="sendGift">{{ t('分享歌曲') }}</button><button class="button secondary premium-studio-entry" type="button" @click="premiumOpen=true"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3v7m0 4v7M12 3v12m0 4v2M19 3v2m0 4v12M2 10h6v4H2zm7 5h6v4H9zm7-10h6v4h-6z"/></svg><span>{{ t('定制化音乐制作') }}</span></button></template>
          <template v-else><button class="button secondary sound-direction-mobile" :disabled="submitting||!!pendingTask||submissionUnknown||settingsBusy" @click="adjustMusic"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 9v6M10 4v16M15 7v10M20 10v4"/></svg>{{ t('旋律与声音') }}</button><div><button class="button creation-primary" :disabled="submitting||(!pendingTask&&!submissionUnknown&&(!modelReady||!lyricsReady))" @click="generate">{{ submitting?t('正在准备歌曲…'):submissionUnknown?t('核对生成进度'):pendingTask?t('查看生成进度'):modelReady?t('确认歌词并生成歌曲'):t('歌曲生成暂未开放') }}</button><span class="save-info">{{ lyricDirty?t('请先点击保存，保存当前歌词后再生成歌曲。'):t('确认已保存的歌词后，再开始谱曲。') }}</span></div></template>
        </div>
        <p v-if="!modelReady&&!asset" class="notice">{{ t('音乐生成暂不可用，可以先编辑并保存歌词，稍后再试。') }}</p>
        <AudioPanel id="work-audio" tabindex="-1" :key="work.id+'-'+mediaRefresh" :work="work" :cover="artwork" @selected="asset=$event" @compare="sectionEditor?.compare($event)"/>
        <SharePanel v-if="asset" :open="giftStage" :cover-id="covers?.currentCoverId" @close="giftStage=false" @created="shared=true" :work-id="work.id" :revision="giftRevision" :asset="asset"/>
      </div>
      <aside class="work-overview">
        <h2>{{ work.recipient?t('关于这份心意'):t('创作灵感') }}</h2><p v-if="work.recipient">{{ t('收件人：') }}{{ work.recipient }}</p><p class="private-message">{{ work.message||work.story }}</p><small>{{ t('故事和草稿仅本人可见，分享由你主动创建。') }}</small>
        <div class="model-status"><h3>{{ t('音乐生成') }}</h3><p class="next-song-settings">{{ nextSettings }}</p><p v-if="allowance">{{ allowance.dailyUnlimited?t('今日歌曲创作不限次数'):t('今日还可创作 {remaining} 次歌曲',{remaining:allowance.dailyRemaining}) }}</p><p>{{ t('保存和修改歌词不消耗歌曲创作次数。') }}</p><p v-if="pendingTask">{{ t(statusLabels[pendingTask.status]||pendingTask.status) }}</p>
          <details v-if="tasks.length" class="creation-fold task-history"><summary>{{ t('查看创作记录') }}</summary><div v-for="task in tasks" :key="task.id" class="task-entry"><strong>{{ task.title }}</strong><span>{{ t(statusLabels[task.status]||task.status) }}</span><p v-if="task.status==='SUBMISSION_UNKNOWN'">{{ t('这次创作结果还在确认中，请稍后刷新，避免重复提交。') }}</p><p v-if="task.errorCode==='QUERY_RETRY'">{{ t('查询暂时失败，稍后自动继续查询。') }}</p><button v-if="task.status==='FAILED'&&task.errorCode==='AUDIO_ARCHIVE_FAILED'" class="button secondary" :disabled="submitting" @click="retryArchive(task)">{{ t('仅重试保存音频') }}</button><small>{{ formatDate(task.createdAt) }}</small></div></details>
          <button class="text-button" @click="refreshTasks">{{ t('刷新进度') }}</button>
        </div>
      </aside>
    </div>
  </template>
  <PremiumStudioDialog v-if="work&&asset&&premiumOpen" :open="premiumOpen" :work-id="work.id" :asset="asset" :title="songTitle" :cover="artwork" @close="premiumOpen=false"/>
  <MusicGenerationDialog :open="generationOpen" :status="generationStatus" :title="trackedTask?.title||work?.title||''" :error="submissionIssue" :sync-issue="progressIssue||trackedTask?.errorCode==='QUERY_RETRY'" :created-at="trackedTask?.createdAt" :submit-after="trackedTask?.nextAttemptAt" :cancelling="cancelling" :cover="artwork" @cancel="cancelGeneration" @close="generationOpen=false" @view="viewGeneratedMusic"/>
</template>
<style scoped>
.work-primary-bar > .premium-studio-entry { display:inline-flex; align-items:center; justify-content:center; gap:8px; min-height:50px; padding:12px 22px; border:1px solid #c38a32; border-radius:26px; background:linear-gradient(120deg,#ffe9b5,#f3cd77); color:#704311; box-shadow:0 5px 16px #b9782226; font-size:15px; font-weight:650; line-height:1.4; }
.premium-studio-entry svg { width:19px; height:19px; flex:none; fill:none; stroke:currentColor; stroke-width:1.6; stroke-linecap:round; stroke-linejoin:round; }
.premium-studio-entry > span { min-width:0; }
.work-primary-bar > .premium-studio-entry:hover { background:linear-gradient(120deg,#ffe19b,#edbc59); border-color:#a66b1b; color:#593207; }
.premium-studio-entry:focus-visible { outline:2px solid #93601a; outline-offset:3px; }
@media(max-width:600px) {
 .work-primary-bar:has(.premium-studio-entry) { align-items:stretch; }
 .work-primary-bar:has(.premium-studio-entry) > .creation-primary { flex:1.1 1 0; width:auto; min-width:0; min-height:50px; }
 .work-primary-bar > .premium-studio-entry { flex:1 1 0; min-width:0; padding:10px; font-size:12px; gap:6px; }
 .premium-studio-entry svg { width:18px; height:18px; }
}
.story-back-actions .description-back-button { display:inline-flex; align-items:center; justify-content:center; gap:9px; min-height:48px; padding:11px 20px; border:1px solid #ec9e87; border-radius:24px; background:#fff0e8; color:#b93422; font:inherit; font-size:17px; font-weight:600; line-height:1.5; cursor:pointer; text-decoration:none; }
.description-back-button svg { width:20px; height:20px; flex:none; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
.story-back-actions .description-back-button:hover { background:#ffe4d7; border-color:#df6950; }
.description-back-button:focus-visible { outline:2px solid #f93822; outline-offset:3px; }
@media(max-width:600px) { .story-back-actions .description-back-button { font-size:16px; padding-inline:17px; } }
</style>
