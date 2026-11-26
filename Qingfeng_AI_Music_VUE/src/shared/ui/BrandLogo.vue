<script setup lang="ts">
defineProps<{ compact?: boolean }>()
</script>
<template>
  <span class="brand-logo" :class="{ 'brand-logo-compact': compact }">
    <img src="/brand/header-lockup.svg" alt="CHEERFUL MUSIC" lang="en" width="1811" height="426">
  </span>
</template>

<style scoped>
.brand-logo {
  --brand-icon-size: 46px;
  --brand-wordmark-height: 40px;
  --brand-gap: 10px;
  /* Use the former combined width as the space available to the reference image. */
  --brand-lockup-width: calc(var(--brand-icon-size) + var(--brand-gap) + var(--brand-wordmark-height) * 3.8504375);
  --brand-icon-only-width: calc(var(--brand-icon-size) * 3.7966457);
  width: var(--brand-visible-width, var(--brand-lockup-width));
  overflow: hidden;
  margin: 0;
}
.brand-logo > img {
  display: block;
  flex: none;
  width: var(--brand-lockup-width);
  height: auto;
  max-width: none;
}
.brand-logo-compact { --brand-icon-size: 36px; --brand-wordmark-height: 30px; }
@media (max-width: 700px) { .brand-logo { --brand-wordmark-height: 27px; } }
@media (max-width: 380px) { .brand-logo { --brand-wordmark-height: 24px; } }
</style>
