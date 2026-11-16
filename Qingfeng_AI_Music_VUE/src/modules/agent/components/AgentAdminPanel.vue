<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { requestJson, currentCreatorId } from '../../../shared/http/api-client'
const props = defineProps<{ feature: 'payment_risk' | 'editorial' }>()
interface Risk { id: string; provider: string; orderId: string; eventType: string; lastEventId: string; status: string }
const cases = ref<Risk[]>([]), selected = ref<Risk>(), decision = ref<'RELEASE' | 'HOLD'>('HOLD'), reason = ref(''), song = ref(''), featured = ref(false)
const error = ref(''), notice = ref(''), busy = ref(false), confirm = ref(false), uncertain = ref(false)
const owner = currentCreatorId(); let alive = true
const current = () => alive && owner === currentCreatorId()
function changed() { confirm.value = false }
async function load() {
 busy.value = true; error.value = ''; selected.value = undefined; confirm.value = false
 try { const rows = await requestJson<Risk[]>('app/payment-risk/cases'); if (current()) { cases.value = rows; uncertain.value = false } }
 catch (e) { if (current()) error.value = e instanceof Error ? e.message : '当前账号没有此权限，或读取失败。' }
 finally { if (current()) busy.value = false }
}
async function submit() {
 if (busy.value || uncertain.value || !current()) return
 if (props.feature === 'payment_risk' && (!selected.value || reason.value.trim().length < 10)) { error.value = '请选择记录并填写至少10字审核理由。'; return }
 if (props.feature === 'editorial' && !/^[a-f0-9-]{36}$/i.test(song.value)) { error.value = '请输入真实发布歌曲ID。'; return }
 if (!confirm.value) { confirm.value = true; return }
 busy.value = true; error.value = ''
 try {
  if (props.feature === 'payment_risk') await requestJson(`app/payment-risk/cases/${encodeURIComponent(selected.value!.id)}/review`, 'POST', { expectedEventId: selected.value!.lastEventId, decision: decision.value, reason: reason.value.trim() })
  else await requestJson(`app/discover/editor/songs/${encodeURIComponent(song.value)}`, 'PUT', { featured: featured.value })
  if (current()) { notice.value = '操作已完成。'; confirm.value = false; uncertain.value = true }
 } catch (e) { if (current()) { error.value = e instanceof Error ? e.message : '结果待核对，请勿重复提交。'; uncertain.value = true; confirm.value = false } }
 finally { if (current()) busy.value = false }
}
onMounted(() => { if (props.feature === 'payment_risk') void load() })
onBeforeUnmount(() => { alive = false })
</script>
<template><section><h3>{{ feature === 'payment_risk' ? '资金风险人工审核' : '发现页精选管理' }}</h3><p>仅已授权运营账号可以操作。请核对具体记录后确认。</p>
<template v-if="feature === 'payment_risk'"><button :disabled="busy" @click="load">刷新并核对记录</button><select v-model="selected" :disabled="busy || uncertain" @change="changed"><option :value="undefined">选择审核记录</option><option v-for="item in cases" :key="item.id" :value="item">{{ item.provider }} · {{ item.orderId }} · {{ item.eventType }} · {{ item.status }}</option></select><select v-model="decision" :disabled="busy || uncertain" @change="changed"><option value="HOLD">维持冻结</option><option value="RELEASE">解除冻结</option></select><label>审核理由<textarea v-model="reason" minlength="10" maxlength="1000" :disabled="busy || uncertain" @input="changed"/></label></template>
<template v-else><label>发布歌曲 ID<input v-model="song" :disabled="busy || uncertain" @input="changed"></label><label><input v-model="featured" type="checkbox" :disabled="busy || uncertain" @change="changed">设为精选</label></template>
<p v-if="confirm" role="alert">请再次核对上方记录和选择，确认后将执行该操作。</p><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p><p v-if="uncertain">请先核对结果，再关闭并重新打开面板处理其他记录。</p><button type="button" :disabled="busy || uncertain" @click="submit">{{ confirm ? '确认执行上述操作' : '核对操作' }}</button></section></template>
<style scoped>label,select{display:block;margin:12px 0}textarea{display:block;min-width:300px;min-height:90px}</style>
