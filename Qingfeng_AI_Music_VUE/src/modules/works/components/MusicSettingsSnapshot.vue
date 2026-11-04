<script setup lang="ts">
import {ref,watch} from 'vue'
import {t,locale} from '../../../shared/i18n'
import {loadTaskMusicSettings,type MusicDirection} from '../api/music-settings'
const props=defineProps<{workId:string;taskId:string}>(),snapshot=ref<MusicDirection|null>(null)
let turn=0
watch(()=>[props.workId,props.taskId],async()=>{const current=++turn;snapshot.value=null;try{const value=await loadTaskMusicSettings(props.workId,props.taskId);if(current===turn)snapshot.value=value}catch{/* 老版作品仍显示原风格与声音记录。 */}},{immediate:true})
</script>
<template><details v-if="snapshot" class="music-snapshot"><summary>{{ t('这版歌曲的音乐设置') }}</summary><p>{{ locale==='en'?snapshot.summaryEn:snapshot.summaryZh }}</p><small>{{ t('记录的是生成当时的选择，修改当前设置不会改变这版歌曲。') }}</small></details></template>
