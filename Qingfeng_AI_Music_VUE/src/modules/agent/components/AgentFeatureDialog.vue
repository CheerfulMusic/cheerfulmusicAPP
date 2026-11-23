<script setup lang="ts">
import { defineAsyncComponent, onMounted, onBeforeUnmount, ref } from 'vue'
import { currentCreatorId, uploadForm, requestJson } from '../../../shared/http/api-client'
import type { AgentFeatureTarget } from '../model/assistant-result'
const props = defineProps<{ target: AgentFeatureTarget }>()
const emit = defineEmits<{ close: []; send: [text: string]; refresh: [] }>()
const Admin = defineAsyncComponent(() => import('./AgentAdminPanel.vue'))
const Gift = defineAsyncComponent(() => import('../../gift/pages/PublicGiftPage.vue'))
const Artist = defineAsyncComponent(() => import('../../artist/pages/MyArtistProfilePage.vue'))
const Discover = defineAsyncComponent(() => import('../../artist/pages/DiscoverPage.vue'))
const Analytics = defineAsyncComponent(() => import('../../analytics/pages/AnalyticsPage.vue'))
const Work = defineAsyncComponent(() => import('../../works/pages/WorkDetailPage.vue'))
const MV = defineAsyncComponent(() => import('../../works/components/MusicVideoEditor.vue'))
const dialog = ref<HTMLDialogElement>(), busy = ref(false), error = ref(''), imageId = ref(''), segment = ref(1), hold = ref(3), duration = ref(0)
const workEditor = ref<{ canClose(): boolean }>()
const nestedPath=ref('')
function openLocal(path:string){
 const url=new URL(path,location.origin)
 if(url.origin===location.origin)nestedPath.value=url.pathname+url.search+url.hash
}
function openLink(event:MouseEvent){
 const link=event.target instanceof Element?event.target.closest<HTMLAnchorElement>('a[href]'):null
 if(!link || link.download || link.hasAttribute('download') || link.getAttribute('href')?.startsWith('#'))return
 if(new URL(link.href).origin!==location.origin)return
 event.preventDefault();event.stopPropagation();openLocal(link.href)
}
const owner = currentCreatorId(); let alive = true
const current = () => alive && owner === currentCreatorId()
function close() { if (workEditor.value && !workEditor.value.canClose()) return; if (!busy.value) { dialog.value?.close(); emit('close'); emit('refresh') } }
async function upload(event: Event) {
 const input = event.target as HTMLInputElement, file = input.files?.[0]
 if (!file || busy.value || !props.target.workId) return
 if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 12 * 1024 * 1024) { error.value = '请选择不超过12MB的JPG或PNG图片。'; return }
 busy.value = true; error.value = ''
 try {
  const body = new FormData(); body.append('workId', props.target.workId); body.append('file', file)
  const result = await uploadForm<{ imageId: string }>('app/music-videos/images', body)
  if (current()) imageId.value = result.imageId
 } catch (e) { if (current()) error.value = e instanceof Error ? e.message : '上传失败' }
 finally { if (current()) busy.value = false; input.value = '' }
}
function apply() {
 if (!current() || !imageId.value || !duration.value) return
 const count = Math.ceil(duration.value / 30000), seconds = Math.min(30, Math.ceil(duration.value / 1000) - (segment.value - 1) * 30)
 if (!Number.isInteger(segment.value) || segment.value < 1 || segment.value > count || !Number.isInteger(hold.value) || hold.value < 1 || hold.value > seconds) { error.value = '段落和停留时间超出歌曲范围，请调整。'; return }
 emit('send', `请预览作品 ${props.target.workId} 音频 ${props.target.assetId} 的MV分镜：将已上传图片 ${imageId.value} 放在第${segment.value}段结尾，停留${hold.value}秒，图片时长免费。保留其他已确认设置，展示更新后的分镜和总费用，暂不生成视频。`)
 close()
}
onMounted(async () => {
 dialog.value?.showModal()
 if (props.target.feature === 'mv_image') try {
  const result = await requestJson<{ durationMillis: number }>(`app/music-videos/options?workId=${encodeURIComponent(props.target.workId!)}&assetId=${encodeURIComponent(props.target.assetId!)}`, 'GET', undefined, 60000)
  if (current()) duration.value = result.durationMillis
 } catch (e) { if (current()) error.value = e instanceof Error ? e.message : '读取时长失败' }
})
onBeforeUnmount(() => { alive = false; dialog.value?.close() })
</script>
<template>
 <MV v-if="target.feature === 'mv' && target.workId && target.assetId" :work-id="target.workId" :asset-id="target.assetId" :title="target.title || '音乐 MV'" @close="close"/>
 <dialog v-else ref="dialog" class="agent-feature-dialog" aria-label="Agent 功能面板" @click.capture="openLink" @cancel.prevent="nestedPath ? nestedPath='' : close()">
  <header><h2>对话内操作</h2><button type="button" :disabled="busy" @click="close">关闭并返回对话</button></header>
  <section v-if="nestedPath" class="agent-nested-view"><button type="button" @click="nestedPath=''">返回上一步</button><iframe :src="nestedPath" title="作品操作" sandbox="allow-same-origin allow-scripts allow-forms allow-downloads allow-modals"/></section>
  <div v-show="!nestedPath"><Admin v-if="target.feature === 'payment_risk' || target.feature === 'editorial'" :feature="target.feature"/>
  <Artist v-else-if="target.feature === 'artist'"/>
  <Gift v-else-if="target.feature === 'gift'" :gift-token="target.giftToken"/>
  <Discover v-else-if="target.feature === 'discover'"/>
  <Analytics v-else-if="target.feature === 'analytics'"/>
  <Work ref="workEditor" v-else-if="target.feature === 'work'" :embedded-work-id="target.workId" @open-within-agent="openLocal"/>
  <section v-else-if="target.feature === 'mv_image'">
   <h3>上传 MV 结尾图片（免费）</h3><p>图片由服务器合成，不调用视频模型；占用段内时间，歌曲总时长保持不变。</p>
   <input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="upload">
   <label>第几段（每段30秒）<input v-model.number="segment" type="number" min="1" :max="Math.ceil(duration / 30000)"></label>
   <label>段尾停留秒数<input v-model.number="hold" type="number" min="1" max="30"></label>
   <p v-if="busy" role="status">正在上传…</p><p v-if="imageId" role="status">图片已上传，可以返回对话调整分镜。</p>
   <p v-if="error" role="alert">{{ error }}</p><button type="button" :disabled="busy || !imageId || !duration" @click="apply">带入对话，预览分镜与费用</button>
  </section>
  </div>
 </dialog>
</template>
<style scoped>
.agent-nested-view iframe{width:100%;height:65vh;border:0;margin-top:10px}
.agent-feature-dialog{width:min(1100px,94vw);max-height:90vh;border:1px solid #efd9cc;border-radius:20px;padding:24px;background:#fffafa;color:#342f32}.agent-feature-dialog::backdrop{background:#24171a80}.agent-feature-dialog header{display:flex;align-items:center;justify-content:space-between}.agent-feature-dialog label{display:block;margin:16px 0}.agent-feature-dialog input[type=number]{margin-left:12px;width:80px}
</style>
