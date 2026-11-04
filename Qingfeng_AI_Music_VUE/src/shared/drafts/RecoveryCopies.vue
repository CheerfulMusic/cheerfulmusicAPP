<script setup lang="ts" generic="T">
import {ref,onMounted,onBeforeUnmount,watch} from 'vue'
import {t,formatDate} from '../i18n'
import {listRecoveryCopies,type RecoveryCopy} from './recovery'
const props=defineProps<{storageKey:string;validate:(value:unknown)=>value is T}>(),emit=defineEmits<{restore:[value:T]}>()
const copies=ref<RecoveryCopy<unknown>[]>([])
function refresh(){copies.value=listRecoveryCopies(props.storageKey,props.validate)}
function choose(copy:RecoveryCopy<unknown>){if((copy.id===props.storageKey||copy.id.startsWith(props.storageKey+':tab:'))&&props.validate(copy.value)&&window.confirm(t('恢复会替换当前编辑，当前内容也会另存一份。确定继续吗？')))emit('restore',copy.value)}
watch(()=>props.storageKey,()=>{copies.value=[];refresh()},{flush:'sync'})
function preview(value:unknown){const v=value as {draft?:{recipient?:string;story?:string;message?:string};title?:string;lyrics?:string};return v.draft?[v.draft.recipient,v.draft.story||v.draft.message].filter(Boolean).join('：').slice(0,160):[v.title,v.lyrics].filter(Boolean).join('：').slice(0,160)}
onMounted(()=>{refresh();window.addEventListener('storage',refresh)})
onBeforeUnmount(()=>window.removeEventListener('storage',refresh))
</script>
<template><details class="recovery-copies" @toggle="refresh"><summary>{{ t('查看保留的本机草稿') }}</summary><p>{{ t('不同标签的草稿分别保留，恢复前请核对时间。') }}</p><button type="button" class="text-button" @click="refresh">{{ t('刷新本机副本') }}</button><p v-if="!copies.length">{{ t('没有其他未保存的本机副本。') }}</p><article v-for="copy in copies" :key="copy.id"><p>{{ preview(copy.value) }}</p><button type="button" class="button secondary" @click="choose(copy)">{{ t('恢复此副本') }} · {{ formatDate(copy.savedAt) }}</button></article></details></template>
