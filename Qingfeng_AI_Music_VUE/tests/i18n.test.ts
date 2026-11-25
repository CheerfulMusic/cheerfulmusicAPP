// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { english } from '../src/shared/i18n/en'
import { siteOrigin } from '../src/shared/i18n/home-seo'

beforeEach(() => {
  localStorage.clear()
  document.head.replaceChildren()
  document.documentElement.removeAttribute('lang')
  vi.resetModules()
})
afterEach(() => { vi.unstubAllGlobals() })

describe('Chinese and English interface', () => {
  it('keeps the Chinese search homepage in Chinese for a first visit', async () => {
    document.documentElement.lang = 'zh-CN'
    const canonical = document.createElement('link')
    canonical.rel = 'canonical'
    canonical.href = siteOrigin + '/'
    canonical.setAttribute('data-site-seo', '')
    document.head.append(canonical)

    const { locale, localeStorageKey, t, updatePageLocale } = await import('../src/shared/i18n')
    updatePageLocale('/')

    expect(locale.value).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(document.title).toBe('青妙Ai')
    expect(t('保存草稿')).toBe('保存草稿')
    expect(localStorage.getItem(localeStorageKey)).toBeNull()
    updatePageLocale('/en/')
    expect(document.documentElement.lang).toBe('en')
    expect(document.title).toBe('cheerfulmusic Ai | Everyone Can Be a Creator')
    updatePageLocale('/')
    expect(document.documentElement.lang).toBe('zh-CN')
  })

  it('keeps the manual homepage preference when visiting a localized search result', async () => {
    const { localeStorageKey, setLocale, updatePageLocale } = await import('../src/shared/i18n')
    setLocale('en')
    updatePageLocale('/discover')
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(localStorage.getItem(localeStorageKey)).toBe('en')
    updatePageLocale('/')
    expect(document.documentElement.lang).toBe('en')
  })

  it.each(['en', 'zh-CN'] as const)('restores a manually selected %s preference after reload', async language => {
    const first = await import('../src/shared/i18n')
    first.setLocale(language === 'en' ? 'zh-CN' : 'en')
    first.setLocale(language)
    expect(localStorage.getItem(first.localeStorageKey)).toBe(language)

    vi.resetModules()
    const restored = await import('../src/shared/i18n')
    restored.updatePageLocale('/')
    expect(restored.locale.value).toBe(language)
    expect(document.documentElement.lang).toBe(language)
  })

  it('still switches when browser storage is blocked and keeps inserted user text intact', async () => {
    vi.stubGlobal('localStorage', { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } })
    const { locale, setLocale, t } = await import('../src/shared/i18n')
    expect(locale.value).toBe('en')
    expect(t('亲爱的{recipient}，', { recipient: '妈妈 {someone} <3' })).toBe('Dear 妈妈 {someone} <3,')
    expect(t('请求失败（503），请检查服务状态')).toContain('(503)')
    setLocale('zh-CN')
    expect(t('把你的故事，')).toBe('把你的故事，')
  })

  it('translates demo display fields while preserving business values and original samples', async () => {
    const { setLocale } = await import('../src/shared/i18n')
    const { tracks, usePreviewTracks } = await import('../src/modules/home/model/tracks')
    const localized = usePreviewTracks(), snapshot = JSON.stringify(tracks)
    setLocale('en')
    expect(localized.value.slice(0,3).map(track=>track.title)).toEqual(['Love in the Everyday','走了那么远','Where the Sea Breeze Leads'])
    expect(localized.value.filter(track=>track.authoredTitle).map(track=>track.title)).toEqual(tracks.filter(track=>track.authoredTitle).map(track=>track.title))
    expect(localized.value[0]!.occasion).toBe('恋爱纪念')
    expect(localized.value[0]!.audio).toBe(tracks[0]!.audio)
    setLocale('zh-CN')
    expect(localized.value.slice(0,3).map(track=>track.title)).toEqual(['把平凡唱给你','走了那么远','去有海风的地方'])
    expect(JSON.stringify(tracks)).toBe(snapshot)
  })

  it('localizes dates and default covers while retaining custom cover paths', async () => {
    const { setLocale, formatDate, defaultCover, localizedCover } = await import('../src/shared/i18n')
    setLocale('en')
    expect(formatDate('2026-11-02T12:00:00Z')).toContain('Nov')
    expect(defaultCover()).toBe('/media/brand-cover-light-en.svg')
    expect(localizedCover('/media/custom.jpg')).toBe('/media/custom.jpg')
    setLocale('zh-CN')
    expect(localizedCover('/media/brand-cover-en.svg')).toBe('/media/brand-cover-light.svg')
  })

  // This scan reads the full source tree and can exceed the default timeout on Windows.
  it('has English copy for every literal interface key and keeps interpolation placeholders', () => {
    const missing: string[] = []
    for (const file of globSync('src/**/*.{vue,ts}')) {
      if (file.endsWith('en.ts')) continue
      for (const match of readFileSync(file, 'utf8').matchAll(/\bt\((['"])(.*?)\1/g)) {
        const key = match[2]!
        if (/[\u3400-\u9fff]/.test(key) && !(key in english)) missing.push(`${file}: ${key}`)
      }
    }
    expect(missing).toEqual([])
    for (const [key, value] of Object.entries(english)) {
      expect([...value.matchAll(/\{\w+\}/g)].map(m => m[0]).sort(), key)
        .toEqual([...key.matchAll(/\{\w+\}/g)].map(m => m[0]).sort())
    }
  }, 15_000)
})
