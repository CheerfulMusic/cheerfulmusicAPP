import { t } from '../../../shared/i18n'
import type { LibraryItem } from '../api/works'

export function songBaseTitle(workTitle?: string | null, generatedTitle?: string | null) {
  const title = workTitle?.trim()
  const generated = generatedTitle?.trim()
  return title && title !== '智能创作' ? title : generated || title || ''
}

export function songVersionTitle(title: string, version?: number) {
  return version ? t('{title} · 版本 {version}', { title, version }) : title
}

export function librarySongTitle(item: LibraryItem) {
  const { title, recipient } = item.work
  const saved = item.audioOrigin === 'GENERATED'
    ? songBaseTitle(title, item.songTitle)
    : item.songTitle || title
  return saved || (recipient ? t('写给{recipient}的歌', { recipient }) : t('我的原创歌曲'))
}
