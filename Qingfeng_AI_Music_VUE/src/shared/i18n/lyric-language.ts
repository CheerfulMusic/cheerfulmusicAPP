export const lyricLanguageLabels = {
  'zh-CN': '中文',
  en: 'English',
  mixed: '中英混合',
  ja: '日语',
  ko: '韩语',
  es: '西班牙语',
} as const

export const customLyricLanguagePrefix = 'custom:'
export const customLyricLanguageMax = 48
export const lyricLanguageError = '请输入有效的歌词语言名称，例如巴西葡萄牙语。'
const customName = /^\p{L}[\p{L}\p{M} \p{Pd}'’()]{0,47}$/u

export function isPresetLyricLanguage(value: string): value is keyof typeof lyricLanguageLabels {
  return Object.hasOwn(lyricLanguageLabels, value)
}

export function customLyricLanguageName(value: string) {
  return value.startsWith(customLyricLanguagePrefix) ? value.slice(customLyricLanguagePrefix.length) : undefined
}

export function selectedLyricLanguage(value: unknown): string | undefined {
  if (typeof value !== 'string') return
  if (isPresetLyricLanguage(value)) return value
  const name = customLyricLanguageName(value)?.trim()
  if (name && name.length <= customLyricLanguageMax && customName.test(name)) return customLyricLanguagePrefix + name
}

export function isLyricLanguageDraft(value: unknown): value is string {
  return typeof value === 'string' && (isPresetLyricLanguage(value)
    || (customLyricLanguageName(value)?.length ?? Infinity) <= customLyricLanguageMax)
}
