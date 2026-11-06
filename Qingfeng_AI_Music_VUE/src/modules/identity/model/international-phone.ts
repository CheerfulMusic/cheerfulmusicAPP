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
export function internationalPhone(raw: string, country: PhoneCountry): string | undefined {
  const value = raw.trim().replace(/^00/, '+')
  if (!value || !/^[+\d\s().-]+$/.test(value)) return undefined
  try {
    const parsed = parsePhoneNumberFromString(value, { defaultCountry: country, extract: false })
    return parsed?.isValid() && !parsed.ext ? parsed.number : undefined
  } catch { return undefined }
}
