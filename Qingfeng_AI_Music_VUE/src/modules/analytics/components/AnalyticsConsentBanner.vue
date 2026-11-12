<script setup lang="ts">
import { useAnalyticsConsent } from '../../../shared/analytics/useAnalyticsConsent'

const { isUndecided, isAccepted, accept, reject, withdraw, manage } = useAnalyticsConsent()
</script>

<template>
  <!-- 未决定：完整同意横幅（独立分析 Cookie 同意，不与注册/服务条款混为一谈） -->
  <div v-if="isUndecided" class="consent-banner" role="dialog" aria-label="分析数据使用同意">
    <p class="consent-text">
      我们使用匿名数据（仅页面路径、停留时长，绝不含歌词、邮箱或手机号）来了解网站使用情况以改进产品。
      这需要你的明确同意；拒绝不会影响你使用本站的任何功能。
    </p>
    <div class="consent-actions">
      <button class="consent-accept" type="button" @click="accept">接受分析</button>
      <button class="consent-reject" type="button" @click="reject">拒绝</button>
    </div>
  </div>

  <!-- 已接受：保留撤回入口 -->
  <div v-else-if="isAccepted" class="consent-chip">
    <span>已同意匿名分析</span>
    <button class="consent-link" type="button" @click="withdraw">撤回</button>
  </div>

  <!-- 已拒绝：保留更改入口 -->
  <div v-else class="consent-chip">
    <span>已拒绝分析采集</span>
    <button class="consent-link" type="button" @click="manage">更改</button>
  </div>
</template>

<style scoped>
.consent-banner {
  position: fixed; left: 16px; right: 16px; bottom: 16px; z-index: 50;
  max-width: 560px; margin: 0 auto; padding: 16px 18px; border-radius: 12px;
  background: #ffffff; color: #1f2430; box-shadow: 0 8px 30px rgba(0, 0, 0, .18);
  display: flex; flex-direction: column; gap: 12px; font-size: 13px; line-height: 1.6;
}
.consent-text { margin: 0; }
.consent-actions { display: flex; gap: 10px; justify-content: flex-end; }
.consent-accept {
  background: #2f6df6; color: #fff; border: 0; border-radius: 8px;
  padding: 8px 16px; cursor: pointer; font-size: 13px;
}
.consent-reject {
  background: transparent; color: #5b6472; border: 1px solid #d7dbe2;
  border-radius: 8px; padding: 8px 16px; cursor: pointer; font-size: 13px;
}
.consent-chip {
  position: fixed; right: 16px; bottom: 16px; z-index: 50;
  display: flex; gap: 8px; align-items: center;
  padding: 8px 12px; border-radius: 999px; background: #ffffff; color: #5b6472;
  box-shadow: 0 4px 16px rgba(0, 0, 0, .12); font-size: 13px;
}
.consent-link {
  background: none; border: 0; color: #2f6df6; cursor: pointer;
  text-decoration: underline; padding: 0; font-size: 13px;
}
</style>
