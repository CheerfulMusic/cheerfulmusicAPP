import { needsExternalDownloadBrowser, showBrowserDownloadHelp } from '../../../shared/browser-download'
import { currentCreatorId, ensureCreator, requestJson, ApiError } from '../../../shared/http/api-client'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { t } from '../../../shared/i18n'
import { loadCredits } from '../../../shared/identity/credit-balance'

export interface DownloadStatus { unlocked: boolean; price: number }
export async function downloadStatus(path: string): Promise<DownloadStatus> {
  await ensureCreator()
  return requestJson<DownloadStatus>(path)
}
const pendingDownloads = new Map<string, Promise<boolean>>()
export function downloadSong(path: string, url: string, filename: string): Promise<boolean> {
  // WeChat can block attachment delivery after unlock. Guide before any paid operation.
  if (needsExternalDownloadBrowser()) { showBrowserDownloadHelp(); return Promise.resolve(false) }
  const pending = pendingDownloads.get(path)
  if (pending) return pending
  const operation = startDownload(path, url, filename).finally(() => pendingDownloads.delete(path))
  pendingDownloads.set(path, operation)
  return operation
}

function sameAccount(owner: string) {
  if (currentCreatorId() !== owner) throw new ApiError(t('账号已切换，请重新下载。'), 409, 'DOWNLOAD_ACCOUNT_CHANGED')
}

function uncertain(error: unknown) {
  return error instanceof ApiError && (error.status === 0 || error.status === 408 || error.status >= 500 && error.status < 600)
}

async function recoverUnlock(path: string, owner: string): Promise<boolean> {
  // An interrupted POST can still commit. Only poll status; never resubmit the paid operation.
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    sameAccount(owner)
    try {
      const status = await requestJson<DownloadStatus>(path, 'GET', undefined, Math.min(5000, deadline - Date.now()))
      sameAccount(owner)
      if (status.unlocked) return true
    } catch (error) { if (!uncertain(error)) throw error }
    const delay = Math.min(2000, deadline - Date.now())
    if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay))
  }
  return false
}

async function startDownload(path: string, url: string, filename: string): Promise<boolean> {
  try {
    await ensureCreator()
    const owner = currentCreatorId()
    const status = await requestJson<DownloadStatus>(path)
    sameAccount(owner)
    const prompt = path.startsWith('public/gifts/')
      ? t('首次下载此作品需 {price} 积分，同一作品已分享给你的其他版本可免费下载。确认解锁？', { price: status.price })
      : t('首次下载此作品需 {price} 积分，解锁后可免费下载此作品的其他版本。确认解锁？', { price: status.price })
    if (!status.unlocked && status.price > 0 && !window.confirm(prompt)) return false
    if (!status.unlocked) {
      try {
        sameAccount(owner)
        const result = await requestJson<DownloadStatus>(path, 'POST', {}, 30000)
        if (!result.unlocked) throw new ApiError('', 0, 'DOWNLOAD_UNLOCK_PENDING')
      } catch (error) {
        if (!uncertain(error)) throw error
        if (!await recoverUnlock(path, owner)) throw new ApiError(t('下载解锁结果尚未确认，请稍后重试；已解锁的作品不会重复扣费。'), 0, 'DOWNLOAD_UNLOCK_PENDING')
      } finally { void loadCredits().catch(() => undefined) }
    }
    sameAccount(owner)
    const link = document.createElement('a'); link.href = url; link.download = filename
    document.body.appendChild(link); link.click(); link.remove()
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 'INVITE_REQUIRED') return false
    if (error instanceof ApiError && error.code === 'DOWNLOAD_LOGIN_REQUIRED') showLoginDialog()
    else window.alert(error instanceof Error ? error.message : t('下载失败，请稍后重试。'))
    return false
  }
}
