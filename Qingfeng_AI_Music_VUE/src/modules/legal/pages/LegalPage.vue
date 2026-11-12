<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import terms from '../content/terms-2026-11-13-draft-2.json'
const route=useRoute()
const privacy=computed(()=>route.path==='/legal/privacy')
</script>
<template>
  <article class="legal-page">
    <RouterLink to="/account">← 返回账号</RouterLink>
    <h1>{{ privacy?'隐私政策':terms.title }}</h1>
    <template v-if="!privacy">
      <p class="legal-meta">初稿 · 版本 {{ terms.version }} · 文件标注生效日期 {{ terms.effectiveDate }}</p>
      <p class="legal-notice">以下草稿基于提供文件修订了新人积分数量，供本轮开发核对；法务确认后再发布更新。</p>
      <div lang="en" class="legal-body"><p v-for="(paragraph,index) in terms.paragraphs" :key="index">{{ paragraph }}</p></div>
    </template>
    <div v-else class="legal-notice" role="status"><p>隐私政策正文待提供。</p><p>现有附件仅包含服务条款，没有隐私政策正文。本页不代表隐私政策已发布或已取得用户同意。</p><RouterLink to="/legal/terms">查看用户服务条款（含积分与退款规则）</RouterLink></div>
  </article>
</template>
<style scoped>
.legal-page{max-width:900px;margin:0 auto;padding:48px 24px 80px;color:var(--text);overflow-wrap:anywhere}.legal-page h1{font-size:clamp(24px,4vw,34px);line-height:1.5;margin:24px 0 14px}.legal-page a{color:var(--accent);text-decoration:underline;text-underline-offset:3px}.legal-meta{color:var(--muted);font-size:13px}.legal-notice{padding:18px;border:1px solid var(--line);border-radius:12px;background:var(--surface);line-height:1.8;margin:24px 0}.legal-body{font-size:16px;line-height:1.9}.legal-body p{white-space:pre-wrap;margin:0 0 22px}
</style>
