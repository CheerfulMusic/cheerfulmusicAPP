<script setup lang="ts">
import { t, formatDate, locale } from '../../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import type { Work } from '../../studio'
import { listRevisions, saveRevision, confirmRevision, generateLyrics, type Revision } from '../api/detail'
const props = defineProps<{ work: Work }>()
const route = useRoute(), router = useRouter()
let mounted = true
onBeforeUnmount(() => { mounted = false })
const emit = defineEmits<{ selected: [revision: Revision | null]; dirty: [value: boolean] }>()
const versions = ref<Revision[]>([]), active = ref<Revision | null>(null), title = ref(props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient})), text = ref(''), busy = ref(true), error = ref(''), notice = ref('')
const aiReady = ref(false), composing = ref(false), language = ref<string>(locale.value), requestKey = ref('')
const originalTitle = title.value
const dirty = computed(() => title.value !== (active.value?.title || originalTitle) || text.value !== (active.value?.lyrics || ''))
watch([dirty, active, composing], () => { emit('dirty', dirty.value); emit('selected', dirty.value || composing.value ? null : active.value) }, { immediate: true })
function select(value: Revision | null) { active.value = value; title.value = value?.title || originalTitle; text.value = value?.lyrics || '' }
async function showRevision(value: Revision) {
  select(value)
  await nextTick()
  if (mounted) await router.replace({ query: { ...route.query, revision: value.id } })
}
async function choose(event: Event) { const input = event.target as HTMLSelectElement; if (dirty.value && !window.confirm(t('切换版本会放弃未保存的歌词，是否继续？'))) { input.value = active.value?.id || ''; return } const value = versions.value.find(r => r.id === input.value); if (value) await showRevision(value); else select(null); notice.value = ''; error.value = '' }
onMounted(async () => { try { const [history,system] = await Promise.all([listRevisions(props.work.id),getSystemInfo()]); versions.value = history; aiReady.value = system.capabilities.lyricsGeneration; if (versions.value.length) select(versions.value.find(item => item.id === route.query.revision) || versions.value[0]!) } catch (e) { error.value = e instanceof Error ? e.message : '歌词读取失败' } finally { busy.value = false } })
async function save() { busy.value = true; error.value = ''; notice.value = ''; try { const created = await saveRevision(props.work.id, props.work.version, title.value, text.value); versions.value.unshift(created); await showRevision(created); notice.value = '已保存新版本，原来的歌词版本保持不变。' } catch (e) { error.value = e instanceof Error ? e.message : '保存失败' } finally { busy.value = false } }
async function confirm() { if (!active.value || dirty.value) return; busy.value = true; error.value = ''; try { const revision = await confirmRevision(props.work.id, active.value.id); versions.value = versions.value.map(r => r.id === revision.id ? revision : r); select(revision); notice.value = '此版本已确认。后续修改请另存新版本。' } catch (e) { error.value = e instanceof Error ? e.message : '确认失败' } finally { busy.value = false } }
watch(language, () => { requestKey.value = '' })
async function compose() {
  if (busy.value || !aiReady.value) return
  if (dirty.value && !window.confirm(t('生成新歌词后将显示新版本，当前未保存的修改会被替换，是否继续？'))) return
  busy.value = true; composing.value = true; error.value = ''; notice.value = ''; requestKey.value ||= crypto.randomUUID()
  try {
    const created = await generateLyrics(props.work.id, props.work.version, requestKey.value, language.value)
    versions.value = [created, ...versions.value.filter(item => item.id !== created.id)]
    await showRevision(created); requestKey.value = ''; notice.value = '歌词已生成并保存为新版本，请先阅读修改，再确认用于歌曲生成。'
  } catch (e) { error.value = e instanceof Error ? e.message : '歌词生成失败，请稍后再试' }
  finally { busy.value = false; composing.value = false }
}
</script>
<template><section class="detail-panel"><div class="detail-heading"><h2>{{ t('把想说的话，写进歌里。') }}</h2><span class="origin-tag">{{ active?.source === 'DEEPSEEK' ? t('AI创作歌词') : t('手动歌词') }}</span></div><p class="detail-muted">{{ t('先把想对TA说的话写下来。每次保存都会保留一个歌词版本，方便你慢慢修改。') }}</p><div v-if="aiReady" class="lyrics-composer"><label class="field">{{ t('歌词语言') }}<select v-model="language" :disabled="busy"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option></select></label><button class="button" :disabled="busy" @click="compose">{{ composing ? t('正在编写歌词…') : versions.length ? t('重新生成歌词') : t('根据故事生成歌词') }}</button><p class="save-info">{{ t('将根据已保存的故事和祝福编写歌词，使用AI服务会产生用量。生成后请先检查并确认。') }}</p></div><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p><label v-if="versions.length" class="field">{{ t('历史版本（最近100条）') }}<select :value="active?.id || ''" :disabled="busy" @change="choose"><option value="">{{ t('新写一版') }}</option><option v-for="revision in versions" :key="revision.id" :value="revision.id">{{ revision.confirmed ? t("已确认") : t("待确认") }} · {{ formatDate(revision.createdAt) }}</option></select></label><label class="field">{{ t('歌曲名称') }}<input v-model="title" maxlength="100" :disabled="busy"></label><label class="field">{{ t('歌词') }}<textarea v-model="text" maxlength="12000" class="lyrics-edit" :disabled="busy" :placeholder="t('写下主歌、副歌，或者你想唱给TA听的话……')"></textarea></label><div class="actions"><button class="button" :disabled="busy || !title.trim() || !text.trim() || !dirty" @click="save">{{ busy ? t("正在处理…") : t("保存为新版本") }}</button><button class="button secondary" :disabled="busy || !active || dirty || active.confirmed" @click="confirm">{{ active?.confirmed && !dirty ? t("当前版本已确认") : t("确认此版本") }}</button></div><p class="save-info">{{ dirty ? t("存在未保存修改，请先保存新版本。") : active ? t("分享和后续生成将使用选定的已确认版本。") : t("填写并保存后，可以确认这版歌词。") }}</p></section></template>
