<script setup lang="ts">
import { t } from '../i18n'
defineProps<{ current: number }>()
const labels = ['写下灵感', '修改歌词', '生成与试听', '制作音乐礼物', '分享']
</script>
<template><ol class="creation-steps" :aria-label="t('制作进度')"><li v-for="(label,index) in labels" :key="label" :class="{current:current===index,done:current>index}" :aria-current="current===index?'step':undefined"><span>{{ current>index?'✓':index+1 }}</span><strong>{{ t(label) }}</strong></li></ol></template>
