<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'
import { t } from '../i18n'
import { usePlayer, type RepeatMode } from './store'

const props = defineProps<{ inPlaylist?: boolean; disabled?: boolean }>()
const player = usePlayer()
const labels = { default: '默认播放', one: '单曲循环', list: '列表循环' } as const
const modes = ['default', 'one', 'list'] as const
const open = ref(false)
const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const options = ref<HTMLElement>()
const id = useId()

async function toggleMenu() {
  open.value = !open.value
  if (!open.value) return
  await nextTick()
  options.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus()
}

function choose(mode: RepeatMode) {
  player.setRepeatMode(mode)
  open.value = false
  trigger.value?.focus()
}

function onEscape(event: KeyboardEvent) {
  if (!open.value) return
  event.preventDefault()
  event.stopPropagation()
  open.value = false
  trigger.value?.focus()
}

function onFocusOut(event: FocusEvent) {
  if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) open.value = false
}

watch(() => props.disabled, disabled => { if (disabled) open.value = false })
watch(open, (visible, _, cleanup) => {
  if (!visible) return
  function onPointerDown(event: PointerEvent) {
    if (!root.value?.contains(event.target as Node)) open.value = false
  }
  document.addEventListener('pointerdown', onPointerDown, true)
  cleanup(() => document.removeEventListener('pointerdown', onPointerDown, true))
})
</script>

<template>
  <div ref="root" class="playback-mode" :class="{ 'in-playlist': inPlaylist }" @keydown.esc="onEscape" @focusout="onFocusOut">
    <button ref="trigger" type="button" class="mode-trigger" :class="{ 'is-active': player.repeatMode === 'one' || player.repeatMode === 'list' }"
      :disabled="disabled" :aria-expanded="open" :aria-controls="open ? id : undefined"
      :aria-label="t('播放模式：{mode}', { mode: t(labels[player.repeatMode]) })"
      :title="disabled ? t('播放此歌单后可切换模式') : t(labels[player.repeatMode])" @click="toggleMenu">
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M17 2.5 21 6.5l-4 4M21 6.5H7a4 4 0 0 0-4 4M7 21.5l-4-4 4-4M3 17.5h14a4 4 0 0 0 4-4" />
        <path v-if="player.repeatMode === 'one'" d="m11 11 1.5-1v4" />
      </svg>
    </button>
    <div v-if="open" :id="id" ref="options" class="mode-options" role="group" :aria-label="t('播放模式')">
      <button v-for="mode in modes" :key="mode" type="button" :aria-pressed="player.repeatMode === mode"
        :disabled="mode === 'list' && !player.hasQueue" @click="choose(mode)">
        {{ t(labels[mode]) }}
        <span v-if="player.repeatMode === mode" aria-hidden="true">✓</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.playback-mode { position:relative; flex:none; }
.mode-trigger { display:grid; place-items:center; width:36px; height:36px; padding:0; border-radius:8px; color:var(--muted); }
.mode-trigger svg { width:20px; height:20px; }
.mode-trigger.is-active, .mode-trigger:hover:not(:disabled) { color:var(--accent-text); background:var(--accent-soft, #f08d7524); }
.mode-trigger:disabled, .mode-options button:disabled { opacity:.5; cursor:default; }
.mode-options {
  position:absolute; z-index:2; inset:auto 0 calc(100% + 8px) auto; width:max-content; min-width:138px;
  padding:4px; border:1px solid var(--line); border-radius:10px; background:var(--surface); color:var(--ink);
  box-shadow:0 7px 24px #0002;
}
.in-playlist .mode-options { inset:calc(100% + 6px) 0 auto auto; }
.mode-options button {
  display:flex; align-items:center; justify-content:space-between; gap:12px; width:100%; min-height:36px;
  padding:6px 9px; border-radius:6px; text-align:start; font-size:13px;
}
.mode-options button[aria-pressed="true"] { color:var(--accent-text); font-weight:600; }
.mode-options button:hover:not(:disabled) { background:var(--accent-soft, #f08d7524); }
@media (max-width:700px) {
  .mode-trigger, .mode-options button { min-height:44px; }
  .mode-trigger { width:44px; }
}
</style>
