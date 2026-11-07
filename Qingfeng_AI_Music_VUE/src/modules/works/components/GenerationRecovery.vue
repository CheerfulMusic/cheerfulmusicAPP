<script setup lang="ts">
import {computed,onBeforeUnmount,ref} from 'vue'
import type {GenerationTask} from '../api/detail'
import {t} from '../../../shared/i18n'
import {generationProblem} from '../model/generation-recovery'
const props=defineProps<{task:GenerationTask;busy:boolean}>()
const emit=defineEmits<{refresh:[];close:[]}>()
const now=ref(Date.now()),timer=window.setInterval(()=>now.value=Date.now(),1000)
onBeforeUnmount(()=>window.clearInterval(timer))
const canClose=computed(()=>!['QUERY_REVIEW_REQUIRED','PROVIDER_DELAYED'].includes(props.task.errorCode||'')&&Number.isFinite(Date.parse(props.task.updatedAt))&&now.value-Date.parse(props.task.updatedAt)>=180000)
const reviewing=computed(()=>['QUERY_REVIEW_REQUIRED','PROVIDER_DELAYED'].includes(props.task.errorCode||''))
function finish(){if(window.confirm(t('结束等待不会取消平台任务，也不会返还本次额度；平台可能已受理或计费。确认结束本次等待？')))emit('close')}
</script>
<template><section class="notice generation-recovery" role="status" :aria-label="t('核对本次音乐创作')"><strong>{{ t('这次提交结果还在核对') }}</strong><p>{{ t(generationProblem(task.errorCode)||'已有平台任务编号时会自动查询原任务；尚未取得编号时，不会重复提交。你也可以稍后结束本次等待。') }}</p><p v-if="!reviewing">{{ t('结束等待只结束本站追踪，平台可能已经计费，已用额度会保留。再次创作需要你主动发起。') }}</p><div class="actions"><button class="button secondary" :disabled="busy" @click="$emit('refresh')">{{ t('刷新核对状态') }}</button><button v-if="!reviewing" class="button secondary" :disabled="busy||!canClose" @click="finish">{{ t('结束本次等待') }}</button></div><small>{{ t(reviewing?'关闭页面不影响后台核对，已有歌曲仍可试听和分享。':'至少等待3分钟后可结束无平台任务编号的等待；有编号的任务继续自动查询。') }}</small></section></template>
