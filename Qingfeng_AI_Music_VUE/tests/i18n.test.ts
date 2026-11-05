import { afterEach, describe, expect, it, vi } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { english } from '../src/shared/i18n/en'

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

describe('Chinese and English interface', () => {
  it('starts in Chinese and restores a saved English preference after reload', async () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key), setItem: (key: string, value: string) => storage.set(key, value) })
    const first = await import('../src/shared/i18n')
    expect(first.locale.value).toBe('zh-CN')
    first.setLocale('en')
    vi.resetModules()
    const restored = await import('../src/shared/i18n')
    expect(restored.locale.value).toBe('en')
    expect(restored.t('保存草稿')).toBe('Save draft')
  })

  it('still switches when browser storage is blocked and keeps inserted user text intact', async () => {
    vi.stubGlobal('localStorage', { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } })
    const { setLocale, t } = await import('../src/shared/i18n')
    setLocale('en')
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
    expect(localized.value.map(track=>track.title)).toEqual(['Love in the Everyday','A Light Left On','Where the Sea Breeze Leads'])
    expect(localized.value[0]!.occasion).toBe('恋爱纪念')
    expect(localized.value[0]!.audio).toBe(tracks[0]!.audio)
    setLocale('zh-CN')
    expect(localized.value.map(track=>track.title)).toEqual(['把平凡唱给你','留一盏灯等你','去有海风的地方'])
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
  })
})
