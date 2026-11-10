<script setup lang="ts">
import {t} from '../../../shared/i18n'
</script>
<template>
 <section class="song-loading" role="status" aria-busy="true" :aria-label="t('正在读取作品…')">
  <span class="loading-label">{{ t('正在读取作品…') }}</span>
  <div class="loading-cover" aria-hidden="true"><span>♪</span></div>
  <div class="loading-copy" aria-hidden="true"><i/><i class="title"/><i/><div class="loading-actions"><b/><b/><b/></div></div>
  <div class="loading-lyrics" aria-hidden="true"><i/><i/><i/><i/><i/></div>
 </section>
</template>
<style scoped>
.song-loading{position:relative;display:grid;grid-template-columns:minmax(160px,240px) minmax(200px,1fr) minmax(220px,1fr);align-items:center;gap:32px;min-height:360px;margin:26px 0;padding:42px 32px;border:1px solid #efdcdf;border-radius:24px;background:#fff;overflow:hidden}
.loading-label{position:absolute;left:32px;top:16px;font-size:13px;color:#867477}
.loading-cover{display:grid;place-items:center;aspect-ratio:1;border-radius:18px;background:#fff5f1;color:#efc6bd;font-size:52px}
.loading-copy i,.loading-lyrics i,.loading-actions b{display:block;height:12px;border-radius:8px;background:#f3e9e9}
.loading-copy i{width:50%;margin:22px 0}.loading-copy .title{width:85%;height:28px}.loading-actions{display:flex;gap:10px;margin-top:36px}.loading-actions b{width:30%;height:44px;border-radius:24px}.loading-lyrics{border-left:1px solid #f3e9e9;padding-left:28px}.loading-lyrics i{width:80%;margin:30px 0}.loading-lyrics i:nth-child(even){width:60%}
.loading-copy,.loading-lyrics{animation:breathe 1.8s ease-in-out infinite}@keyframes breathe{50%{opacity:.5}}
@media(max-width:850px){.song-loading{grid-template-columns:160px 1fr}.loading-lyrics{grid-column:1/-1;border-left:0;border-top:1px solid #f3e9e9;padding:0}.loading-lyrics i{margin:24px 0}}
@media(max-width:600px){.song-loading{grid-template-columns:1fr;gap:10px;padding:46px 22px 22px;min-height:420px}.loading-label{left:22px}.loading-cover{width:150px;margin:auto}.loading-copy .title{height:22px}.loading-actions{margin-top:24px}.loading-lyrics i:nth-child(n+4){display:none}}
@media(prefers-reduced-motion:reduce){.loading-copy,.loading-lyrics{animation:none}}
</style>
