import { getCountries, getCountryCallingCode, parsePhoneNumberFromString, type CountryCode } from 'libphonenumber-js/max'

export type PhoneCountry = CountryCode
export const phoneCountries = getCountries()
export const callingCode = (country: PhoneCountry) => `+${getCountryCallingCode(country)}`

export function countryOptions(language: string) {
  const names = new Intl.DisplayNames([language], { type: 'region' })
  return phoneCountries.map(country => ({ country, name: names.of(country) || country, code: callingCode(country) }))
    .sort((a, b) => a.name.localeCompare(b.name, language))
}

/** Keep the selected region's national prefix rules; send only a complete E.164 number. */
function parseInternationalPhone(raw: string, country: PhoneCountry) {
  const value = raw.trim().replace(/^00/, '+')
  if (!value || !/^[+\d\s().-]+$/.test(value)) return undefined
  try {
    const parsed = parsePhoneNumberFromString(value, { defaultCountry: country, extract: false })
    return parsed?.isValid() && !parsed.ext ? parsed : undefined
  } catch { return undefined }
}

export function internationalPhone(raw: string, country: PhoneCountry): string | undefined {
  return parseInternationalPhone(raw, country)?.number
}

/** SMS requires a mobile number with a region resolved from the number itself. */
export function internationalSmsPhone(raw: string, country: PhoneCountry): { number: string; region: PhoneCountry } | undefined {
  const parsed = parseInternationalPhone(raw, country)
  return parsed?.country && parsed.getType() === 'MOBILE' ? { number: parsed.number, region: parsed.country } : undefined
}

/** An older server can still offer mainland China SMS without an international region list. */
export function smsRegionAvailable(phoneEnabled: boolean, allowedRegions: readonly string[] | undefined, region: PhoneCountry): boolean {
  if (!phoneEnabled) return false
  return allowedRegions ? allowedRegions.includes(region) : region === 'CN'
}
