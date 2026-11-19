export interface Gift {
  hasCover?: boolean
  title: string
  recipient: string | null
  message: string | null
  lyrics: string
  audioOrigin: 'IMPORTED' | 'GENERATED'
  createdAt: string
  expiresAt: string
}

export function takeInitialGift(token: string): Gift | undefined {
  if (typeof document === 'undefined' || location.pathname !== `/gift/${token}`) return undefined
  const node = document.getElementById('qf-initial-gift')
  if (!node) return undefined
  node.remove()
  try {
    const value = JSON.parse(node.textContent || '')
    if (value?.token !== token || typeof value.title !== 'string' || typeof value.lyrics !== 'string' ||
        (value.recipient !== null && typeof value.recipient !== 'string') ||
        (value.message !== null && typeof value.message !== 'string') ||
        !['IMPORTED', 'GENERATED'].includes(value.audioOrigin) || typeof value.hasCover !== 'boolean' ||
        typeof value.createdAt !== 'string' || typeof value.expiresAt !== 'string' ||
        !Number.isFinite(Date.parse(value.createdAt)) || !(Date.parse(value.expiresAt) > Date.now())) return undefined
    return value
  } catch {
    return undefined
  }
}
