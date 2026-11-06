import { t } from '../i18n'

/** Keep only the final four digits even if an upstream response contains more. */
export function maskedPhoneLabel(phoneMasked?: string): string {
  const last4 = phoneMasked?.match(/(\d{4})\D*$/)?.[1]
  return last4 ? t('手机号尾号 {last4}', { last4 }) : ''
}
