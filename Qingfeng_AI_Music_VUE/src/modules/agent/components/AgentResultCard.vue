<script setup lang="ts">
import { computed, ref } from 'vue'
import { locale } from '../../../shared/i18n'
import { resultMediaUrl, resultInviteUrl, type AssistantResult, type AgentCommand } from '../model/assistant-result'
const props = defineProps<{ result: AssistantResult; disabled: boolean; current: boolean }>()
defineEmits<{ command: [command: AgentCommand] }>()
const en = computed(() => locale.value === 'en')
const stale = computed(() => props.result.kind === 'confirmation' && !props.current)
const media = (value: unknown, type: 'audio' | 'image') => resultMediaUrl(value, type, window.location.origin)
const inviteUrl = (value: unknown) => resultInviteUrl(value, window.location.origin)
const copyNotice = ref('')
async function copyInvite(value: unknown) {
  const url = inviteUrl(value); if (!url) return
  try { await navigator.clipboard.writeText(url); copyNotice.value = en.value ? 'Invitation link copied' : '邀请链接已复制' }
  catch { copyNotice.value = en.value ? 'Select and copy the invitation link above.' : '请选中上方邀请链接手动复制。' }
}
</script>
<template>
  <section class="agent-result-card" :class="{ 'agent-result-confirmation': result.kind === 'confirmation' }" :aria-label="en ? 'Operation result' : '操作结果'">
    <ol v-if="result.items.length" class="agent-result-items">
      <li v-for="(item, index) in result.items" :key="`${item.id}:${index}`">
        <span class="agent-result-index" aria-hidden="true">{{ index + 1 }}</span>
        <img v-if="media(item.data.imageUrl, 'image')" :src="media(item.data.imageUrl, 'image')" :alt="item.title" loading="lazy"/>
        <div class="agent-result-detail"><strong>{{ item.title }}</strong><p v-if="item.subtitle">{{ item.subtitle }}</p>
          <small v-if="item.data.current === true">{{ en ? 'Current selection' : '当前选择' }}</small>
          <blockquote v-if="typeof item.data.excerpt === 'string'" class="agent-source-excerpt"><small>{{ en ? 'Source excerpt' : '来源摘要' }}</small><p>{{ item.data.excerpt }}</p></blockquote>
          <audio v-if="media(item.data.audioUrl, 'audio')" :src="media(item.data.audioUrl, 'audio')" controls preload="none" :aria-label="item.title"/>
          <div v-if="inviteUrl(item.data.invitePath)" class="agent-result-invite"><input readonly :value="inviteUrl(item.data.invitePath)" :aria-label="en ? 'Invitation link' : '邀请链接'" @focus="($event.target as HTMLInputElement).select()"/><button type="button" @click="copyInvite(item.data.invitePath)">{{ en ? 'Copy invitation' : '复制邀请链接' }}</button></div>
        </div>
      </li>
    </ol>
    <p v-if="copyNotice" class="agent-result-expired" role="status">{{ copyNotice }}</p>
    <p v-if="stale" class="agent-result-expired">{{ en ? 'This confirmation is no longer current. Describe your request again.' : '这条确认已不是当前请求，请重新说明要执行的操作。' }}</p>
    <div v-if="result.commands.length" class="agent-result-commands">
      <button v-for="(command,index) in result.commands" :key="index" type="button" :disabled="disabled || stale" @click="$emit('command', command)">{{ command.label }}</button>
    </div>
  </section>
</template>
<style scoped>
.agent-source-excerpt{margin:8px 0;padding:8px 12px;border-left:3px solid #e8ccba;background:#fff;white-space:pre-wrap;overflow-wrap:anywhere}
.agent-result-invite{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.agent-result-invite input{min-width:0;flex:1;padding:8px;border:1px solid #e8ccba;border-radius:6px;background:#fff;color:#725541}.agent-result-invite button{border:1px solid #e8ccba;border-radius:6px;background:#fff;color:#965d3d;cursor:pointer}
.agent-result-card{margin-top:10px;padding:14px;border:1px solid #efd9cc;border-radius:14px;background:#fffaf7;max-width:680px;color:#59463d}
.agent-result-confirmation{border-color:#e5a66f;background:#fff5e9}.agent-result-items{display:grid;gap:0;list-style:none;margin:0;padding:0}.agent-result-items li{display:flex;gap:12px;align-items:flex-start;padding:12px 0;border-bottom:1px solid #f1e5dc}.agent-result-items li:last-child{border-bottom:0}.agent-result-index{display:grid;place-items:center;flex:0 0 25px;height:25px;background:#f8e7db;border-radius:8px;font-size:12px;color:#aa6546}.agent-result-items img{width:64px;height:64px;object-fit:cover;border-radius:8px}.agent-result-detail{min-width:0;flex:1}.agent-result-detail strong{font-size:14px;overflow-wrap:anywhere}.agent-result-detail p{margin:5px 0;color:#8f786b;font-size:12px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.agent-result-detail small{color:#bd643c}.agent-result-detail audio{display:block;max-width:100%;height:36px;margin-top:8px}.agent-result-commands{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.agent-result-commands button{border:1px solid #e8ccba;border-radius:10px;padding:9px 12px;background:#fff;color:#965d3d;cursor:pointer;text-align:left}.agent-result-commands button:disabled{opacity:.5;cursor:not-allowed}.agent-result-commands button:focus-visible{outline:3px solid #ffa279;outline-offset:2px}.agent-result-expired{font-size:12px;color:#8d7365}
</style>
