<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { ApiError, apiUrl, currentCreatorId, ensureCreator, requestJson, uploadForm } from '../../../shared/http/api-client'
import AgentVideoProgress from '../../agent/components/AgentVideoProgress.vue'
import { sceneLabel, videoCost, videoRates, videoTimeline, type VideoPlan, type VideoResolution } from '../model/music-video'
const props = defineProps<{ workId: string; assetId: string; title: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(), titleId = useId()
const plan = ref<VideoPlan>({ title: props.title, ratio: '16:9', resolution: '720P', durationMillis: 0, shots: [] })
const busy = ref(false), loading = ref(true), error = ref(''), notice = ref(''), projectId = ref(''), unknown = ref(false)
const quoted = ref<{ plan: VideoPlan; credits: number; generatedSeconds: number }>()
const projects = ref<{ id: string; asset_id: string; state: string; request_key: string }[]>([])
const requestKey = ref(''), cost = computed(() => videoCost(plan.value))
const resolutions: VideoResolution[] = ['480P', '720P', '1080P']
const terminal = new Set(['READY', 'FAILED', 'ASSEMBLY_FAILED', 'CANCELLED'])
const selectedState = ref('')
const hasActive = computed(() => projects.value.some(p => !terminal.has(p.state)))
const locked = computed(() => loading.value || busy.value || unknown.value || !!projectId.value || hasActive.value)
function selectProject(id: string) { projectId.value = id; selectedState.value = projects.value.find(p => p.id === id)?.state || '' }
function progressChanged(state: string) {
  selectedState.value = state
  const project = projects.value.find(p => p.id === projectId.value)
  if (project) project.state = state
}
async function loadOptions() {
  const options = await requestJson<{ durationMillis: number }>(`app/music-videos/options?workId=${encodeURIComponent(props.workId)}&assetId=${encodeURIComponent(props.assetId)}`, 'GET', undefined, 60000)
  plan.value.durationMillis = options.durationMillis; plan.value.shots = videoTimeline(options.durationMillis)
}
async function newProject() {
  if (hasActive.value || !terminal.has(selectedState.value) || busy.value) return
  loading.value = true; error.value = ''
  try {
    if (await findSubmitted(false)) return
    await loadOptions(); projectId.value = ''; selectedState.value = ''; requestKey.value = ''; changed()
  } catch (e) { error.value = message(e) } finally { loading.value = false }
}
let storageKey = ''
const payload = () => ({ workId: props.workId, assetId: props.assetId, plan: plan.value })
const message = (e: unknown) => e instanceof Error ? e.message : '操作失败，请稍后重试。'
function changed() { quoted.value = undefined; error.value = ''; notice.value = '' }
function imageUrl(id: string) { return apiUrl(`app/music-videos/images/${encodeURIComponent(id)}?workId=${encodeURIComponent(props.workId)}`) }
function close() { dialog.value?.close(); emit('close') }
async function findSubmitted(matchRequest = true) {
  const rows = await requestJson<{ id: string; asset_id: string; state: string; request_key: string }[]>(`app/music-videos?workId=${encodeURIComponent(props.workId)}`)
  projects.value = rows.filter(r => r.asset_id === props.assetId)
  const found = projects.value.find(r => !terminal.has(r.state)) || (matchRequest && requestKey.value ? projects.value.find(r => r.request_key === requestKey.value) : undefined)
  if (found) { selectProject(found.id); unknown.value = false; sessionStorage.removeItem(storageKey); notice.value = '已恢复已有 MV 任务，请在这里查看进度，无需重复提交。' }
  return !!found
}
async function checkSubmission() { busy.value = true; error.value = ''; try { if (!await findSubmitted()) notice.value = '尚未查到任务。可使用下方按钮核对并重试原请求，不会创建新的请求标识。' } catch (e) { error.value = message(e) } finally { busy.value = false } }
async function initialize() {
  loading.value = true; error.value = ''
  try {
    await ensureCreator(); storageKey = `qingfeng.mv-submit:${currentCreatorId()}:${props.workId}:${props.assetId}`
    const pending = sessionStorage.getItem(storageKey)
    if (pending) {
      const saved = JSON.parse(pending)
      if (typeof saved.key !== 'string' || !saved.quote?.plan?.shots?.length) throw new Error('无法恢复待核对请求，请保留页面并联系支持。')
      requestKey.value = saved.key; quoted.value = saved.quote; plan.value = saved.quote.plan; unknown.value = true
      await findSubmitted()
    } else {
      if (!await findSubmitted()) await loadOptions()
    }
  } catch (e) { error.value = message(e) } finally { loading.value = false }
}
onMounted(() => { dialog.value?.showModal(); void initialize() })
async function upload(index: number, event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  if (file.size > 12 * 1024 * 1024 || !['image/jpeg', 'image/png'].includes(file.type)) { error.value = '请选择不超过 12 MB 的 JPG 或 PNG 图片。'; input.value = ''; return }
  busy.value = true; changed()
  try {
    const form = new FormData(); form.append('file', file); form.append('workId', props.workId)
    const result = await uploadForm<{ imageId: string }>('app/music-videos/images', form)
    const shot = plan.value.shots[index]; if (!shot) return
    shot.imageId = result.imageId; shot.imageSeconds = Math.min(3, shot.seconds)
  } catch (e) { error.value = message(e) } finally { busy.value = false; input.value = '' }
}
async function aiStoryboard() {
  busy.value = true; changed()
  try { plan.value = await requestJson<VideoPlan>('app/music-videos/storyboard', 'POST', { ...payload(), requestKey: crypto.randomUUID() }, 65000); notice.value = 'AI 分镜已填入，请逐段检查和修改；尚未生成视频。' }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function review() {
  busy.value = true; error.value = ''
  try { quoted.value = await requestJson('app/music-videos/prepare', 'POST', payload(), 60000); plan.value = quoted.value!.plan }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function submit() {
  if (!quoted.value || busy.value || projectId.value || hasActive.value) return
  busy.value = true; error.value = ''; requestKey.value ||= crypto.randomUUID()
  try {
    // Retain the exact confirmed request before sending, including across a reload.
    sessionStorage.setItem(storageKey, JSON.stringify({ key: requestKey.value, quote: quoted.value }))
    const result = await requestJson<{ projectId: string }>('app/music-videos', 'POST', { ...payload(), plan: quoted.value.plan, quotedCredits: quoted.value.credits, requestKey: requestKey.value }, 65000)
    projects.value.unshift({ id: result.projectId, asset_id: props.assetId, state: 'QUEUED', request_key: requestKey.value }); selectProject(result.projectId); unknown.value = false; sessionStorage.removeItem(storageKey)
  } catch (e) {
    error.value = message(e)
    unknown.value = !(e instanceof ApiError) || e.status === 0 || e.status === 408 || e.status >= 500
    if (!unknown.value) {
      sessionStorage.removeItem(storageKey); requestKey.value = ''
      // Another tab may have submitted first. Recover its task before permitting a new request.
      try { if (await findSubmitted()) error.value = '' } catch { unknown.value = true }
    }
  } finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="mv-editor" :aria-labelledby="titleId" @cancel.prevent="close">
    <header><div><h2 :id="titleId">制作完整 MV</h2><p>{{ title }} · 按歌曲时长自动编排</p></div><button type="button" aria-label="关闭 MV 编辑器" @click="close">×</button></header>
    <p v-if="loading" role="status">正在查询已有 MV 任务和歌曲信息…</p>
    <p v-if="error" role="alert" class="mv-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <button v-if="error && !loading && !projectId && !plan.shots.length" type="button" @click="initialize">重新查询 MV 任务</button>
    <section v-if="projectId" class="mv-current" aria-label="当前 MV 任务"><h3>MV 任务进度</h3><p v-if="!terminal.has(selectedState)">任务正在排队、制作或等待核对，请勿重复提交。关闭页面不会取消任务，再次进入可继续查看。</p><AgentVideoProgress :key="projectId" :project-id="projectId" @state-change="progressChanged"/></section>
    <details v-if="projects.length && !unknown"><summary>查看这首歌已有的 MV（{{ projects.length }}）</summary><button v-for="project in projects" :key="project.id" type="button" @click="selectProject(project.id)">查看 MV · {{ project.state }}</button></details>
    <button v-if="projectId && terminal.has(selectedState) && !hasActive" type="button" :disabled="loading || busy" @click="newProject">制作新的 MV</button>
    <template v-if="plan.shots.length && !loading && !projectId">
      <fieldset :disabled="locked"><legend>画面设置</legend><label v-for="resolution in resolutions" :key="resolution" class="resolution"><input v-model="plan.resolution" type="radio" :value="resolution" @change="changed">{{ resolution }} · {{ videoRates[resolution] }} 积分/秒</label><label>画幅 <select v-model="plan.ratio" @change="changed"><option>16:9</option><option>9:16</option><option>1:1</option></select></label></fieldset>
      <p>歌曲 {{ (plan.durationMillis / 1000).toFixed(2) }} 秒，每段最长 30 秒。图片放在各段结尾并占用该段时间；上传和展示图片免费。</p>
      <button type="button" class="button secondary" :disabled="locked" @click="aiStoryboard">AI 一键根据歌词和音乐风格生成分镜</button>
      <small>AI 分镜使用现有对话额度，仅填写草稿。也可以直接手动输入各段提示词。</small>
      <fieldset v-for="(shot, i) in plan.shots" :key="i" :disabled="locked" class="mv-scene">
        <legend>{{ i * 30 }}–{{ Math.min((i + 1) * 30, plan.durationMillis / 1000).toFixed(2) }} 秒 · {{ sceneLabel(i, plan.shots.length) }}</legend>
        <label>视频提示词<textarea v-model="shot.prompt" maxlength="2000" rows="3" placeholder="描述人物、场景、动作、镜头和光线；可手动填写或使用 AI 分镜" @input="changed"/></label>
        <div class="mv-image"><img v-if="shot.imageId" :src="imageUrl(shot.imageId)" :alt="`第 ${i + 1} 段结尾图片`"><label>上传结尾图片（免费）<input type="file" accept="image/jpeg,image/png" @change="upload(i, $event)"></label>
          <label v-if="shot.imageId">停留秒数 <input v-model.number="shot.imageSeconds" type="number" min="0" :max="shot.seconds" step="1" @input="changed"></label>
          <button v-if="shot.imageId" type="button" @click="shot.imageId = null; shot.imageSeconds = 0; changed()">移除本段图片</button>
        </div><small>视频 {{ shot.seconds - shot.imageSeconds }} 秒 + 图片 {{ shot.imageSeconds }} 秒</small>
      </fieldset>
      <footer aria-live="polite"><strong>预计 {{ cost.credits }} 积分</strong><span>视频 {{ cost.generatedSeconds }} 秒 × {{ videoRates[plan.resolution] }} 积分/秒；图片 {{ cost.imageSeconds }} 秒免费。不足一秒按一秒计费。</span>
        <button v-if="!quoted && !projectId" type="button" class="button primary" :disabled="locked" @click="review">核对分镜与费用</button>
        <section v-if="quoted && !projectId" class="mv-confirm"><h3>确认生成</h3><p>将按上方完整时间轴生成 {{ plan.resolution }} MV，保留整首歌曲音轨，共收取 <strong>{{ quoted.credits }} 积分</strong>。视频提交后不能保证取消或退款。</p><p v-if="unknown">上次请求结果待核对。请查询任务，或使用原请求重试。</p><button v-if="unknown" type="button" :disabled="busy" @click="checkSubmission">查询原任务</button><button type="button" class="button primary" :disabled="busy" @click="submit">{{ unknown ? '核对并重试原请求' : `确认生成 · ${quoted.credits} 积分` }}</button></section>
      </footer>
    </template>
  </dialog>
</template>
<style scoped>
.mv-current{padding:20px;margin:16px 0;background:#fff0e9;border:1px solid #e6c8be;border-radius:12px}.mv-current h3{margin-top:0}
.mv-editor{width:min(880px,94vw);max-height:90vh;border:1px solid #ecdcd8;border-radius:20px;padding:24px;color:#342f32;background:#fffafa}.mv-editor::backdrop{background:#24171a80}.mv-editor header{display:flex;justify-content:space-between;gap:16px}.mv-editor h2{margin:0}.mv-editor header button{align-self:start;font-size:24px;border:0;background:none;cursor:pointer}.mv-editor fieldset{border:1px solid #eadcda;border-radius:12px;margin:16px 0;padding:16px}.mv-editor label{display:block;margin:8px 0}.mv-editor .resolution{display:inline-flex;margin-right:18px;gap:6px}.mv-editor textarea{display:block;width:100%;box-sizing:border-box;padding:10px;border:1px solid #d9c7c4;border-radius:8px;font:inherit;resize:vertical}.mv-editor input[type=number]{width:72px}.mv-editor small{display:block;margin-top:8px;color:#786567}.mv-image{display:flex;flex-wrap:wrap;gap:16px;align-items:center}.mv-image img{width:110px;height:80px;object-fit:contain;border-radius:8px}.mv-editor footer{display:grid;gap:12px;padding:16px;background:#fff0e9;border-radius:12px}.mv-editor footer span{font-size:13px}.mv-confirm{border-top:1px solid #e6c8be;padding-top:12px}.mv-error{color:#a42525}.mv-editor button:focus-visible,.mv-editor input:focus-visible,.mv-editor textarea:focus-visible{outline:3px solid #ff9986;outline-offset:2px}
</style>
