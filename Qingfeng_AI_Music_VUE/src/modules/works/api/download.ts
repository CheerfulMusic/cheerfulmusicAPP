import { ensureCreator, requestJson, ApiError } from '../../../shared/http/api-client'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { t } from '../../../shared/i18n'
import { loadCredits } from '../../../shared/identity/credit-balance'

export interface DownloadStatus { unlocked: boolean; price: number }
export async function downloadStatus(path: string): Promise<DownloadStatus> {
  await ensureCreator()
  return requestJson<DownloadStatus>(path)
}
export async function downloadSong(path: string, url: string, filename: string): Promise<boolean> {
  try {
    const status = await downloadStatus(path)
    const prompt = path.startsWith('public/gifts/')
      ? t('首次下载此作品需 {price} 积分，同一作品已分享给你的其他版本可免费下载。确认解锁？', { price: status.price })
      : t('首次下载此作品需 {price} 积分，解锁后可免费下载此作品的其他版本。确认解锁？', { price: status.price })
    if (!status.unlocked && status.price > 0 && !window.confirm(prompt)) return false
    if (!status.unlocked) { await requestJson<DownloadStatus>(path, 'POST', {}); void loadCredits().catch(() => undefined) }
    const link = document.createElement('a'); link.href = url; link.download = filename
    document.body.appendChild(link); link.click(); link.remove()
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 'DOWNLOAD_LOGIN_REQUIRED') showLoginDialog()
    else window.alert(error instanceof Error ? error.message : t('下载失败，请稍后重试。'))
    return false
  }
}
