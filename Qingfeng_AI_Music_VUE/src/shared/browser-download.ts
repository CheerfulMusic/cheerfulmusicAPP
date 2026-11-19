import { readonly, ref } from 'vue'
const open = ref(false)
export const browserDownloadOpen = readonly(open)
export function needsExternalDownloadBrowser(): boolean {
  return /MicroMessenger|WindowsWechat|MacWechat|\bWeChat\//i.test(navigator.userAgent)
}
export function showBrowserDownloadHelp() { open.value = true }
export function closeBrowserDownloadHelp() { open.value = false }
