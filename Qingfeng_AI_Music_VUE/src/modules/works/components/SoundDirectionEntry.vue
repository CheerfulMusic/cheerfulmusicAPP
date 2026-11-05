<script setup lang="ts">
import { t } from '../../../shared/i18n'
defineProps<{ styleLabel: string; voiceLabel: string; disabled: boolean }>()
defineEmits<{ choose: [] }>()
</script>

<template>
  <button type="button" class="sound-direction-entry" :disabled="disabled" :aria-label="t('选择旋律与声音')" @click="$emit('choose')">
    <span class="sound-direction-icon" aria-hidden="true"><svg viewBox="0 0 32 32"><path d="M5 13v6M11 7v18M17 3v26M23 9v14M29 13v6"/></svg></span>
    <span class="sound-direction-copy">
      <strong>{{ t('选择旋律与声音') }}</strong>
      <span class="sound-direction-description">{{ t('选好音乐风格和演唱声音，再让歌词成为一首歌。') }}</span>
      <span class="sound-direction-summary"><span>{{ styleLabel }}</span><span>{{ voiceLabel }}</span></span>
    </span>
    <span class="sound-direction-arrow" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></svg></span>
  </button>
</template>

<style scoped>
.sound-direction-entry { display:flex; align-items:center; gap:20px; width:100%; margin:25px 0 0; padding:25px 27px; border:1.5px solid #edb09e; border-radius:20px; background:linear-gradient(115deg,#fff0e6,#fff7f4 65%,#fff1ee); text-align:left; color:var(--ink); box-shadow:0 8px 28px #d36b4910; transition:background .18s,border-color .18s,box-shadow .18s; }
.sound-direction-entry:not(:disabled):hover { background:linear-gradient(115deg,#ffe7d9,#fff1eb); border-color:#e77556; box-shadow:0 10px 30px #d36b491c; }
.sound-direction-entry:focus-visible { outline:2px solid var(--accent-text); outline-offset:4px; }
.sound-direction-entry:disabled { opacity:.55; box-shadow:none; }
.sound-direction-icon { display:grid; place-items:center; width:56px; height:56px; flex-shrink:0; border-radius:17px; background:linear-gradient(135deg,#ff7950,#ff432d); color:#fff; box-shadow:0 5px 14px #ef5f352b; }
.sound-direction-icon svg { width:30px; height:30px; fill:none; stroke:currentColor; stroke-width:2.3; stroke-linecap:round; }
.sound-direction-copy { display:block; flex:1; min-width:0; }
.sound-direction-copy>strong { display:block; font-size:20px; font-weight:650; line-height:1.5; color:#4c2f2b; }
.sound-direction-description { display:block; font-size:12px; color:#8b6159; line-height:1.8; margin-top:5px; }
.sound-direction-summary { display:flex; gap:7px; flex-wrap:wrap; margin-top:12px; }
.sound-direction-summary>span { padding:4px 11px; border:1px solid #f0cbbc; border-radius:20px; background:#fffaf6; color:#a44d39; font-size:11px; line-height:1.6; overflow-wrap:anywhere; }
.sound-direction-arrow { display:grid; place-items:center; width:35px; height:35px; flex-shrink:0; border-radius:50%; background:#fff9f3; border:1px solid #efbfa9; color:#cd5031; }
.sound-direction-arrow svg { width:19px; height:19px; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
@media(max-width:600px) {
  .sound-direction-entry { align-items:flex-start; padding:20px 17px; gap:13px; border-radius:18px; margin-top:22px; }
  .sound-direction-icon { width:43px; height:43px; border-radius:13px; }
  .sound-direction-icon svg { width:25px; height:25px; }
  .sound-direction-copy>strong { font-size:17px; line-height:1.5; }
  .sound-direction-description { font-size:11px; margin-top:6px; }
  .sound-direction-summary { margin-top:11px; gap:6px; }
  .sound-direction-summary>span { padding:4px 9px; font-size:10px; }
  .sound-direction-arrow { width:28px; height:28px; margin-top:5px; }
  .sound-direction-arrow svg { width:16px; height:16px; }
}
@media(prefers-reduced-motion:reduce) { .sound-direction-entry { transition:none; } }
</style>
