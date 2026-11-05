<script setup lang="ts">
import { ref } from 'vue'
import { t } from '../i18n'
import NavIcon from './NavIcon.vue'

const dialog = ref<HTMLDialogElement>()
function open() { if (!dialog.value?.open) dialog.value?.showModal() }
function close() { dialog.value?.close() }
defineExpose({ open, close })
</script>

<template>
  <dialog ref="dialog" class="login-placeholder" aria-labelledby="login-placeholder-title" aria-describedby="login-placeholder-description" @click="event => { if (event.target === event.currentTarget) close() }">
    <div class="login-placeholder-card">
      <button class="login-placeholder-close" type="button" :aria-label="t('关闭登录弹窗')" @click="close"><NavIcon name="close"/></button>
      <img class="login-placeholder-logo" src="/brand/logo-symbol.svg" alt="" width="56" height="56">
      <p class="login-placeholder-brand">Cheerful Music</p>
      <h2 id="login-placeholder-title">{{ t('登录 / 注册') }}</h2>
      <p id="login-placeholder-description">{{ t('登录与注册功能正在准备中，敬请期待。') }}</p>
      <button class="login-placeholder-confirm" type="button" autofocus @click="close">{{ t('知道了') }}</button>
    </div>
  </dialog>
</template>

<style scoped>
.login-placeholder { width:min(420px,calc(100% - 32px)); max-width:none; max-height:calc(100dvh - 32px); padding:0; border:1px solid #eaded8; border-radius:28px; background:#fffaf7; color:#282320; box-shadow:0 24px 90px #30180d33; }
.login-placeholder::backdrop { background:#241b1766; backdrop-filter:blur(6px); }
.login-placeholder-card { position:relative; padding:44px 30px 30px; text-align:center; }
.login-placeholder-close { position:absolute; top:14px; right:14px; display:grid; place-items:center; width:34px; height:34px; padding:7px; border:0; border-radius:50%; color:#786b63; background:transparent; cursor:pointer; }
.login-placeholder-close:hover { background:#f4e9e2; }
.login-placeholder-logo { display:block; margin:0 auto 12px; }
.login-placeholder-brand { margin:0 0 24px; font-size:14px; font-weight:700; }
.login-placeholder h2 { margin:0 0 14px; font-size:26px; }
#login-placeholder-description { margin:0; color:#786b63; font-size:14px; line-height:1.8; }
.login-placeholder-confirm { display:block; width:100%; min-height:48px; margin-top:30px; border:0; border-radius:24px; background:#ff4b32; color:#fff; font:inherit; font-weight:700; cursor:pointer; }
.login-placeholder-confirm:hover { background:#df3d29; }
.login-placeholder button:focus-visible { outline:3px solid #ff937d; outline-offset:3px; }
</style>
