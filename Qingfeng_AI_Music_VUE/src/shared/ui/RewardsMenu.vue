<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { t } from '../i18n'
const open = ref(false), root = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>()
const route = useRoute()
const items = [
  { title:'邀请有礼', detail:'邀请新用户首次登录，送19积分', to:'/account#invite-rewards', icon:'♧' },
  { title:'发布到广场', detail:'发布到广场送2积分', to:'/works', icon:'↗' },
  { title:'改善建议', detail:'改善建议送10-100积分', to:'mailto:labelmanager@cheerfulmusic.co.uk?subject=Cheerful%20Music%20Feedback', icon:'✎' },
]
function close() { open.value = false }
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && open.value) { close(); trigger.value?.focus() } }
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape) })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) })
watch(() => route.fullPath, close)
</script>
<template>
  <div ref="root" class="rewards-menu">
    <button ref="trigger" type="button" class="rewards-trigger" :aria-label="t('积分好礼')" :aria-expanded="open" aria-controls="rewards-panel" @click="open=!open"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M4 10h16v10H4zM3 6h18v4H3zM12 6v14"/><path d="M12 6C6 7 5 1 8 2c2 0 4 4 4 4Zm0 0c6 1 7-5 4-4-2 0-4 4-4 4Z"/></svg></button>
    <nav v-if="open" id="rewards-panel" class="rewards-panel" :aria-label="t('积分好礼')">
      <template v-for="item in items" :key="item.title">
        <component :is="item.to.startsWith('mailto:')?'a':RouterLink" v-bind="item.to.startsWith('mailto:') ? {href:item.to} : {to:item.to}" @click="close"><span class="reward-icon" aria-hidden="true">{{ item.icon }}</span><span><strong>{{ t(item.title) }}</strong><small>{{ t(item.detail) }}</small></span><span aria-hidden="true">›</span></component>
      </template>
    </nav>
  </div>
</template>
<style scoped>
.rewards-menu{position:relative;flex-shrink:0}.rewards-trigger{display:grid;place-items:center;width:36px;height:36px;border:1px solid var(--line);border-radius:50%;background:var(--surface-raised);color:var(--ink);cursor:pointer}.rewards-trigger svg{width:19px;height:19px}.rewards-trigger:hover,.rewards-trigger[aria-expanded=true]{background:var(--accent-soft);color:var(--accent-text)}.rewards-trigger:focus-visible,.rewards-panel a:focus-visible{outline:2px solid var(--accent-text);outline-offset:3px}.rewards-panel{position:absolute;right:0;top:calc(100% + 12px);width:310px;max-width:calc(100vw - 24px);padding:6px 20px;background:var(--surface-raised);border:1px solid var(--line);border-radius:18px;box-shadow:0 16px 48px #22151026;z-index:80}.rewards-panel a{display:flex;align-items:center;gap:12px;padding:20px 0;color:var(--ink);text-decoration:none}.rewards-panel a+a{border-top:1px solid var(--line)}.rewards-panel a:hover{color:var(--accent-text)}.rewards-panel a>span:nth-child(2){flex:1;min-width:0}.rewards-panel strong{display:block;font-size:15px;font-weight:600}.rewards-panel small{display:block;margin-top:7px;font-size:12px;color:var(--muted);line-height:1.5}.reward-icon{font-size:23px;width:22px;text-align:center}@media(max-width:600px){.rewards-panel{position:fixed;top:76px;right:12px}.rewards-trigger{width:32px;height:32px}}
</style>
