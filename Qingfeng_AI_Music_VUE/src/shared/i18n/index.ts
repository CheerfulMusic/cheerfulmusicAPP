import { computed, readonly, ref } from 'vue'
import { platform } from '../../platform'
import { english } from './en'
import { homeLocale, homeSeo, localizedSeoLinks, siteOrigin, type Locale } from './home-seo'
import { discoverLocale, discoverPaths, sitePage, socialMetadata, websiteSchema } from './site-seo'

export type { Locale } from './home-seo'
export const localeStorageKey = 'qingfeng.locale'
function savedLocale(): Locale | undefined {
  try {
    const value = localStorage.getItem(localeStorageKey)
    return value === 'en' || value === 'zh-CN' ? value : undefined
  } catch { return undefined }
}
const selected = ref<Locale>(savedLocale() ?? 'en')
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
type SongMetadata = { title: string; artist: string; description: string; image: string }
let discoverSong: SongMetadata | null | undefined

const discoverSongPath = /^(?:\/en)?\/discover\/songs\/([^/]+)\/(\d+)\/?$/

export function updateDiscoverSongMetadata(path: string, metadata: SongMetadata | null) {
  if (path !== pagePath || !discoverSongPath.test(path)) return
  discoverSong = metadata
  updateDocument()
}

function updateDocument() {
  if (typeof document === 'undefined') return
  const landingPage = sitePage(pagePath, selected.value)
  const pageLanguage = landingPage?.guide ? landingPage.language : selected.value
  document.documentElement.lang = pageLanguage
  if (platform.kind === 'web') {
    document.title = landingPage?.path === '/' ? landingPage.title : homeSeo[pageLanguage].tabTitle
  }
  const songMatch = pagePath.match(discoverSongPath)
  const matchingServerDocument = songMatch && discoverSong === undefined &&
    document.head.querySelector('link[data-site-seo][rel="canonical"]')?.getAttribute('href') ===
      siteOrigin + pagePath.replace(/\/$/, '')
  if (matchingServerDocument) return
  document.head.querySelectorAll('[data-site-seo]').forEach(element => element.remove())
  if (document.head.querySelector('meta[property="og:type"][content="music.song"]')) {
    if (pagePath.startsWith('/gift/')) return
    document.head.querySelectorAll('meta[property^="og:"], meta[name^="twitter:"], meta[name="robots"]')
      .forEach(element => element.remove())
  }
  const songMetadata = songMatch ? discoverSong : undefined
  const songLanguage: Locale = pagePath.startsWith('/en/') ? 'en' : 'zh-CN'
  const songPage = songMetadata && songMatch ? {
    path: pagePath.replace(/\/$/, ''),
    language: songLanguage,
    title: songMetadata.title + ' — ' + songMetadata.artist + ' | ' + homeSeo[songLanguage].brand,
    siteName: homeSeo[songLanguage].brand,
    heading: songMetadata.title,
    description: songMetadata.description.trim() || (songLanguage === 'en'
      ? 'Listen to “' + songMetadata.title + '” by ' + songMetadata.artist + ' on cheerfulmusic.'
      : '在青妙Ai试听' + songMetadata.artist + '发布的《' + songMetadata.title + '》。'),
    links: localizedSeoLinks(pagePath.replace(/\/$/, ''), {
      'zh-CN': '/discover/songs/' + songMatch[1] + '/' + songMatch[2],
      en: '/en/discover/songs/' + songMatch[1] + '/' + songMatch[2],
    }),
  } : undefined
  const page = songPage ?? landingPage
  if (platform.kind !== 'web') document.title = page?.title ?? '青风音乐'
  document.querySelector('meta[name="description"]')?.setAttribute(
    'content', page?.description ?? t('把你的故事唱成歌，送给重要的人。用青风音乐记录回忆，制作专属音乐礼物。'),
  )

  function appendTag(tag: 'link' | 'meta' | 'script', attributes: Record<string, string | undefined>, content?: string) {
    const element = document.createElement(tag)
    element.setAttribute('data-site-seo', '')
    for (const [name, value] of Object.entries(attributes)) {
      if (value !== undefined) element.setAttribute(name, value)
    }
    if (content) element.textContent = content
    document.head.append(element)
  }
  if (songMatch && discoverSong === null) {
    appendTag('meta', { name: 'robots', content: 'noindex' })
  }
  if (!page) return
  for (const attributes of page.links) appendTag('link', attributes)
  for (const attributes of socialMetadata(page)) {
    if (songMetadata) {
      if (attributes.property === 'og:type') attributes.content = 'music.song'
      if (attributes.property === 'og:image' || attributes.name === 'twitter:image') attributes.content = songMetadata.image
      if (attributes.property === 'og:image:alt' || attributes.name === 'twitter:image:alt') attributes.content = songMetadata.title
    }
    appendTag('meta', attributes)
  }
  if (songPage && songMetadata) appendTag('script', { type: 'application/ld+json' }, JSON.stringify({
    '@context': 'https://schema.org',
    '@type': 'MusicRecording',
    url: siteOrigin + songPage.path,
    name: songMetadata.title,
    description: songPage.description,
    byArtist: { '@type': 'Person', name: songMetadata.artist },
    image: songMetadata.image,
  }))
  if (page.path === '/') appendTag('script', { type: 'application/ld+json' }, JSON.stringify(websiteSchema))
}
export function setLocale(value: Locale) {
  selected.value = value
  try { localStorage.setItem(localeStorageKey, value) } catch { /* Language switching still works without browser storage. */ }
  updateDocument()
}
export function updatePageLocale(path: string) {
  if (path !== pagePath) discoverSong = undefined
  pagePath = path
  const songLocale = discoverSongPath.test(path) ? (path.startsWith('/en/') ? 'en' : 'zh-CN') : undefined
  const routeLanguage = homeLocale(path) ?? discoverLocale(path) ?? songLocale
  if (routeLanguage) selected.value = routeLanguage
  updateDocument()
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
