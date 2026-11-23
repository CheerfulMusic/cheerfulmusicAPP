import { setLocale } from '../src/shared/i18n'

// Use a saved Chinese preference for component tests that assert Chinese copy.
// Locale-specific tests explicitly set or clear their preference before asserting behavior.
setLocale('zh-CN')
