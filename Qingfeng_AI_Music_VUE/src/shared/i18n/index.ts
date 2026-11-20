import { readonly, ref } from 'vue'
import { english } from './en'
import { homeLocale, homeSeo, homeSeoLinks, type Locale } from './home-seo'

export type { Locale } from './home-seo'
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
let localizedHome: Locale | undefined
function updateDocument() {
  if (typeof document === 'undefined') return
  document.documentElement.lang = selected.value
  const seo = localizedHome && homeSeo[localizedHome]
  document.title = seo ? seo.title : '青风音乐'
  document.querySelector('meta[name="description"]')?.setAttribute('content', seo ? seo.description : t('把你的故事唱成歌，送给重要的人。用青风音乐记录回忆，制作专属音乐礼物。'))
  document.head.querySelectorAll('link[data-home-seo]').forEach(link => link.remove())
  if (localizedHome) {
    for (const attributes of homeSeoLinks(localizedHome)) {
      const link = document.createElement('link')
      link.setAttribute('data-home-seo', '')
      for (const [name, value] of Object.entries(attributes)) link.setAttribute(name, value)
      document.head.append(link)
    }
  }
}
export function setLocale(value: Locale) {
  selected.value = value
  try { localStorage.setItem(localeStorageKey, value) } catch { /* Language switching still works without browser storage. */ }
  updateDocument()
}
export function updatePageLocale(path: string) {
  localizedHome = homeLocale(path)
  if (localizedHome) setLocale(localizedHome)
  else updateDocument()
}
export function formatDate(value: string | number | Date): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(selected.value, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
export function defaultCover(): string {
  return selected.value === 'en' ? '/media/brand-cover-light-en.svg' : '/media/brand-cover-light.svg'
}
export function localizedCover(cover: string): string {
  return /^\/media\/brand-cover(?:-light)?(?:-en)?\.svg$/.test(cover) ? defaultCover() : cover
}
