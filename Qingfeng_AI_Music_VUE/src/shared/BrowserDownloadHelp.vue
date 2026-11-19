<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { browserDownloadOpen, closeBrowserDownloadHelp } from './browser-download'
import { t } from './i18n'
const dialog = ref<HTMLDialogElement>()
const copied = ref(false)
const page = ref('')
watch(browserDownloadOpen, async open => {
  if (open) {
    copied.value = false
    page.value = window.location.href
    await nextTick()
    dialog.value?.showModal()
  } else dialog.value?.close()
})
async function copyPage() {
  try { await navigator.clipboard.writeText(page.value); copied.value = true }
  catch { document.getElementById('download-page-link')?.focus(); (document.getElementById('download-page-link') as HTMLInputElement)?.select() }
}
</script>
<template>
  <dialog ref="dialog" class="download-help" aria-labelledby="download-help-title" @close="closeBrowserDownloadHelp" @cancel="closeBrowserDownloadHelp">
    <h2 id="download-help-title">{{ t('请在手机浏览器下载歌曲') }}</h2>
    <p>{{ t('微信内置浏览器会限制文件下载。请点击右上角「…」，选择「在浏览器打开」，然后再次点击下载。') }}</p>
    <p>{{ t('也可以复制当前页面链接，用 Safari 或 Chrome 打开。若需要登录，请使用同一账号；已解锁的作品不会重复扣费。') }}</p>
    <p>{{ t('本次操作未发起解锁或扣费。') }}</p>
    <label for="download-page-link">{{ t('当前页面链接') }}</label>
    <input id="download-page-link" :value="page" readonly @focus="($event.target as HTMLInputElement).select()" />
    <div class="download-help-actions"><button type="button" @click="copyPage">{{ copied ? t('链接已复制') : t('复制页面链接') }}</button><button type="button" @click="closeBrowserDownloadHelp">{{ t('关闭') }}</button></div>
  </dialog>
</template>
<style scoped>
.download-help{width:min(440px,calc(100vw - 40px));box-sizing:border-box;border:1px solid #ead9d8;border-radius:20px;padding:24px;background:#fffafb;color:#302526;line-height:1.7;max-height:85dvh;overflow:auto}
.download-help::backdrop{background:#0008}.download-help h2{font-size:20px;margin:0 0 16px}.download-help input{box-sizing:border-box;width:100%;padding:10px;border:1px solid #d8c7c7;border-radius:8px}.download-help-actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:20px}.download-help button{padding:10px 16px;border:1px solid #d8c7c7;border-radius:10px;background:white;cursor:pointer;font:inherit}
</style>
