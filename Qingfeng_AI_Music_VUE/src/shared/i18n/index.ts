import { readonly, ref } from 'vue'
import { english } from './en'

export type Locale = 'zh-CN' | 'en'
export const localeStorageKey = 'qingfeng.locale'
function savedLocale(): Locale {
  try { return localStorage.getItem(localeStorageKey) === 'en' ? 'en' : 'zh-CN' }
  catch { return 'zh-CN' }
}
const selected = ref<Locale>(savedLocale())
export const locale = readonly(selected)

/** Translate interface copy only. Never pass authored stories, names or lyrics here. */
export function t(message: string, params: Record<string, string | number> = {}): string {
  const requestFailure = message.match(/^请求失败（(\d+)），请检查服务状态$/)
  if (requestFailure) return t('请求失败（{status}），请检查服务状态', { status: requestFailure[1]! })
  const uploadFailure = message.match(/^音频导入失败（(\d+)）$/)
  if (uploadFailure) return t('音频导入失败（{status}）', { status: uploadFailure[1]! })
  const copy = selected.value === 'en' ? english[message] ?? message : message
  return copy.replace(/\{(\w+)\}/g, (placeholder, key: string) => String(params[key] ?? placeholder))
}
function updateDocument() {
  if (typeof document === 'undefined') return
  document.documentElement.lang = selected.value
  document.title = selected.value === 'en'
    ? 'Cheerful Music · Turn your story into a song'
    : '青风音乐 · 把你的故事，唱给重要的人'
  document.querySelector('meta[name="description"]')?.setAttribute('content', t('把你的故事唱成歌，送给重要的人。用青风音乐记录回忆，制作专属音乐礼物。'))
}
export function setLocale(value: Locale) {
  selected.value = value
  try { localStorage.setItem(localeStorageKey, value) } catch { /* Language switching still works without browser storage. */ }
  updateDocument()
}
export function initializeLocale() { updateDocument() }
export function formatDate(value: string | number | Date): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(selected.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
export function defaultCover(): string {
  return selected.value === 'en' ? '/media/brand-cover-en.svg' : '/media/brand-cover.svg'
}
export function localizedCover(cover: string): string {
  return /^\/media\/brand-cover(?:-en)?\.svg$/.test(cover) ? defaultCover() : cover
}
