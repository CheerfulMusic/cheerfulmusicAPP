import { computed, readonly, ref } from 'vue'
import { platform } from '../../platform'
import { english } from './en'
import { homeLocale, type Locale } from './home-seo'
import { discoverLocale, discoverPaths, sitePage, socialMetadata, websiteSchema } from './site-seo'

export type { Locale } from './home-seo'
export const localeStorageKey = 'qingfeng.locale'
function savedLocale(): Locale {
  try { return localStorage.getItem(localeStorageKey) === 'zh-CN' ? 'zh-CN' : 'en' }
  catch { return 'en' }
}
const selected = ref<Locale>(savedLocale())
export const locale = readonly(selected)
export const discoverHref = computed(() => platform.kind === 'web' ? discoverPaths[selected.value] : '/discover')

/** Translate interface copy only. Never pass authored stories, names or lyrics here. */
export function t(message: string, params: Record<string, string | number> = {}): string {
  const requestFailure = message.match(/^请求失败（(\d+)），请检查服务状态$/)
  if (requestFailure) return t('请求失败（{status}），请检查服务状态', { status: requestFailure[1]! })
  const uploadFailure = message.match(/^音频导入失败（(\d+)）$/)
  if (uploadFailure) return t('音频导入失败（{status}）', { status: uploadFailure[1]! })
  const copy = selected.value === 'en' ? english[message] ?? message : message
  return copy.replace(/\{(\w+)\}/g, (placeholder, key: string) => String(params[key] ?? placeholder))
}
let pagePath = ''
function updateDocument() {
  if (typeof document === 'undefined') return
  document.documentElement.lang = selected.value
  document.head.querySelectorAll('[data-site-seo]').forEach(element => element.remove())
  if (document.head.querySelector('meta[property="og:type"][content="music.song"]')) {
    if (pagePath.startsWith('/gift/')) return
    document.head.querySelectorAll('meta[property^="og:"], meta[name^="twitter:"], meta[name="robots"]')
      .forEach(element => element.remove())
  }
  const page = sitePage(pagePath, selected.value)
  if (page?.guide) document.documentElement.lang = page.language
  document.title = page?.title ?? '青风音乐'
  document.querySelector('meta[name="description"]')?.setAttribute(
    'content', page?.description ?? t('把你的故事唱成歌，送给重要的人。用青风音乐记录回忆，制作专属音乐礼物。'),
  )
  if (!page) return

  function appendTag(tag: 'link' | 'meta' | 'script', attributes: Record<string, string | undefined>, content?: string) {
    const element = document.createElement(tag)
    element.setAttribute('data-site-seo', '')
    for (const [name, value] of Object.entries(attributes)) {
      if (value !== undefined) element.setAttribute(name, value)
    }
    if (content) element.textContent = content
    document.head.append(element)
  }
  for (const attributes of page.links) appendTag('link', attributes)
  for (const attributes of socialMetadata(page)) appendTag('meta', attributes)
  if (page.path === '/') appendTag('script', { type: 'application/ld+json' }, JSON.stringify(websiteSchema))
}
export function setLocale(value: Locale) {
  selected.value = value
  try { localStorage.setItem(localeStorageKey, value) } catch { /* Language switching still works without browser storage. */ }
  updateDocument()
}
export function updatePageLocale(path: string) {
  pagePath = path
  const songLocale = /^(?:\/en)?\/discover\/songs\/[^/]+\/\d+\/?$/.test(path) ? (path.startsWith('/en/') ? 'en' : 'zh-CN') : undefined
  const routeLanguage = homeLocale(path) ?? discoverLocale(path) ?? songLocale
  if (routeLanguage) setLocale(routeLanguage)
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
