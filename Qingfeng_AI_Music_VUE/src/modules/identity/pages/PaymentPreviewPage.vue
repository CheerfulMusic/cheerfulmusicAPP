<script setup lang="ts">
import CreditsPanel from '../components/CreditsPanel.vue'
</script>

<template>
  <main class="payment-preview">
    <p class="preview-label">本地界面预览</p>
    <h1>看看积分购买页面</h1>
    <p>这里展示的是示例套餐。可以切换英镑和美元，点击按钮不会创建订单或付款。</p>
    <CreditsPanel preview />
  </main>
</template>

<style scoped>
.payment-preview{max-width:1000px;margin:40px auto;padding:0 24px 80px;color:var(--text)}
.payment-preview h1{margin:8px 0;font-size:clamp(28px,5vw,40px)}
.payment-preview p{color:var(--muted);line-height:1.7}
.preview-label{font-size:13px;font-weight:700;color:var(--accent-text)!important}
@media(max-width:640px){.payment-preview{margin:24px auto;padding:0 16px 60px}}
</style>
