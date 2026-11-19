import { expect } from 'vitest'

// Existing interface assertions exercise an explicitly selected Chinese locale.
// Locale tests keep a fresh module so they can verify the real English default.
if (!expect.getState().testPath?.replaceAll('\\', '/').endsWith('/i18n.test.ts')) {
  const { setLocale } = await import('../src/shared/i18n')
  setLocale('zh-CN')
}
