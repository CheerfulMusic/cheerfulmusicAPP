<script setup lang="ts">
import { t, locale } from '../../../shared/i18n/index'
import { onMounted, onBeforeUnmount, reactive, ref, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { loadWork, saveWork, type Work, type DraftInput } from '../api/works'
import SuggestionField from '../components/SuggestionField.vue'
import { recipientSuggestions, occasionSuggestions, styleSuggestions } from '../model/suggestions'
import { continuationRequestKey } from '../model/lyrics-continuation'
import { generateLyrics } from '../../works/api/detail'
const route = useRoute(), router = useRouter(), step = ref(0), busy = ref(false), ready = ref(false), musicReady = ref(false), initialized = ref(false), notice = ref('正在准备你的音乐故事…'), failed = ref(false)
const existing = ref<Work>(), baseline = ref('')
const composing = ref(false)
let mounted = true
onBeforeUnmount(() => { mounted = false })
const draft = reactive<DraftInput>({ recipient: '', occasion: String(route.query.occasion || '恋爱纪念'), story: '', message: '', style: '温暖民谣', title: '' })
const dirty = computed(() => initialized.value && JSON.stringify(draft) !== baseline.value)
const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
onBeforeRouteLeave(() => !dirty.value || window.confirm(t('当前内容尚未保存，确定离开吗？')))
function restore(work: Work) { existing.value = work; Object.assign(draft, { recipient: work.recipient, occasion: work.occasion, story: work.story, message: work.message, style: work.style, title: work.title }); baseline.value = JSON.stringify(draft) }
onMounted(async () => {
  try {
    const capabilities = (await getSystemInfo()).capabilities; ready.value = capabilities.workPersistence; musicReady.value = capabilities.musicGeneration
    if (!ready.value) { notice.value = '暂时无法保存故事，请稍后再试。'; return }
    if (route.query.work) { restore(await loadWork(String(route.query.work))); notice.value = '已找回之前保存的故事。' }
    else { baseline.value = JSON.stringify(draft); notice.value = '故事只对自己可见。换设备前，请先保存作品找回码。' }
  } catch (error) { failed.value = true; notice.value = error instanceof Error ? error.message : '服务暂时不可用' }
  finally { initialized.value = true }
})
function validateDraft(): boolean {
  if (!draft.recipient.trim()) { notice.value = '请先填写收件人的称呼。'; failed.value = true; step.value = 0; return false }
  if (!draft.occasion.trim()) { notice.value = '请选择或填写送歌的时刻。'; failed.value = true; step.value = 0; return false }
  if (!draft.style.trim()) { notice.value = '请选择或填写音乐风格。'; failed.value = true; step.value = 2; return false }
  return true
}
async function save() {
  if (busy.value || !validateDraft()) return
  busy.value = true; failed.value = false
  try { const saved = await saveWork({ ...draft }, existing.value); restore(saved); await router.replace({ path: '/create', query: { work: saved.id } }); notice.value = '草稿已保存，刷新后可以继续编辑。' }
  catch (error) { failed.value = true; notice.value = error instanceof Error ? error.message : '保存失败，请重试' }
  finally { busy.value = false }
}
async function continueMaking() {
  if (busy.value || !ready.value || !validateDraft()) return
  if (!draft.story.trim() && !draft.message.trim()) { failed.value = true; notice.value = '请先在故事中填写一段回忆或祝福，再生成歌词'; step.value = 1; return }
  busy.value = true; composing.value = true; failed.value = false
  notice.value = '正在保存故事并为你编写歌词，请稍候…'
  try {
    const work = existing.value && !dirty.value ? existing.value : await saveWork({ ...draft }, existing.value)
    restore(work)
    const language = locale.value
    const revision = await generateLyrics(work.id, work.version, continuationRequestKey(work.id, work.version, language), language)
    if (mounted) await router.push({ path: `/works/${work.id}`, query: { revision: revision.id } })
  } catch (error) { if (mounted) { failed.value = true; notice.value = error instanceof Error ? error.message : '歌词生成失败，请稍后再试' } }
  finally { busy.value = false; composing.value = false }
}
async function reload() { if (existing.value && (!dirty.value || window.confirm(t('重新载入会替换未保存内容，是否继续？')))) { try { restore(await loadWork(existing.value.id)); notice.value = '已打开之前保存的故事。'; failed.value = false } catch { notice.value = '重新载入失败，请稍后重试'; failed.value = true } } }
</script>
<template><h1>{{ t('让一段故事，有自己的旋律。') }}</h1><p class="backend-state">{{ existing ? t("继续编辑你的音乐礼物") : t("从一个名字，和一件小事开始。") }}</p><div class="draft-steps"><span v-for="(label,index) in ['送给谁','讲述故事','选择旋律与保存']" :key="label" :class="{active:step===index}">{{ index + 1 }}　{{ t(label) }}</span></div><div class="draft-layout"><section class="draft-form"><p class="notice" :class="{error:failed}" role="status">{{ t(notice) }}</p><form @submit.prevent="save"><fieldset class="draft-fields" :disabled="busy">
<div v-show="step===0"><h2>{{ t('这首歌，你想送给谁？') }}</h2><SuggestionField v-model="draft.recipient" :label="t('TA 的称呼')" :placeholder="t('例如：妈妈、小雨、老朋友')" :suggestions="recipientSuggestions" :max-length="80" required/><SuggestionField v-model="draft.occasion" :label="t('在什么特别的时刻？')" :placeholder="t('例如：第一次旅行、相识100天')" :suggestions="occasionSuggestions" :max-length="32" translate-value required/></div>
<div v-show="step===1"><h2>{{ t('讲讲属于你们的故事。') }}</h2><label class="field">{{ t('想写进歌里的回忆') }}<textarea v-model="draft.story" maxlength="4000" :placeholder="t('你们在哪里认识？一起经历过什么？')"></textarea></label><label class="field">{{ t('最想对 TA 说的话') }}<textarea v-model="draft.message" maxlength="500" :placeholder="t('这句话，也会成为礼物的一部分。')" style="min-height:95px"></textarea></label></div>
<div v-show="step===2"><h2>{{ t('给故事，选一种声音。') }}</h2><SuggestionField v-model="draft.style" :label="t('音乐风格')" :placeholder="t('例如：钢琴与木吉他，温柔女声')" :suggestions="styleSuggestions" :max-length="80" translate-value required/><label class="field">{{ t('作品名称（可以稍后修改）') }}<input v-model="draft.title" maxlength="100" :placeholder="t('给这份心意起个名字')"></label><p class="notice">{{ musicReady ? t('保存故事后，确认歌词即可生成歌曲，也可以导入已有音频。') : t('歌曲生成暂未开放。你可以先保存故事、完善歌词，或用已有音频制作音乐礼物。') }}</p></div>
<div class="actions"><button v-if="step>0" class="button secondary" type="button" @click="step--">{{ t('上一步') }}</button><button v-if="step<2" class="button secondary" type="button" @click="step++">{{ t('下一步') }}</button><button class="button" type="submit" :disabled="busy || !ready">{{ busy ? t("正在保存…") : t("保存草稿") }}</button><button v-if="existing" class="button secondary" type="button" :disabled="busy" @click="reload">{{ t('载入最新版本') }}</button></div><p class="save-info">{{ dirty ? t("有尚未保存的修改") : existing ? t("当前修改已保存") : t("从一个小细节开始，保存你的第一份灵感") }}<span v-if="existing"> {{ t('· 版本') }} {{ existing.version + 1 }}</span></p><div v-if="existing" class="actions"><button class="button continue-music-gift" type="button" :disabled="busy || !ready" @click="continueMaking">{{ composing ? t('正在编写歌词…') : t('继续制作音乐礼物 ↗') }}</button><RouterLink class="p-text-link" to="/works">{{ t('我的作品') }}</RouterLink></div><p v-if="existing" class="save-info continue-lyrics-info">{{ t('继续后将根据最新故事生成歌词，你可以修改后再确认。') }}</p></fieldset></form></section><aside class="draft-aside"><h2>{{ t('最动听的歌词，') }}<br>{{ t('藏在平凡的日子里。') }}</h2><img src="/media/family-editorial.png" :alt="t('AI亲情场景示意')"><p>{{ t('一个特别的地点、一句熟悉的口头禅，就已经足够动人。') }}</p></aside></div></template>
