import { ref } from 'vue'
import { currentCreatorId, ensureCreator, requestJson, uploadForm } from '../../../shared/http/api-client'
import { locale } from '../../../shared/i18n'

interface AvatarResponse { imageUrl: string | null; version: string | null }

/** Never let an old identity's response repopulate the next identity's avatar. */
export function useUserAvatar() {
  const avatarUrl = ref(''), busy = ref(false), error = ref('')
  const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
  let generation = 0
  function clear() { generation++; avatarUrl.value = ''; error.value = ''; busy.value = false }
  function imageUrl(value: AvatarResponse): string {
    if (value.imageUrl === null) return ''
    if (value.imageUrl !== '/api/app/avatar/image' || typeof value.version !== 'string' || !value.version) throw new Error('Invalid avatar response')
    return `/api/app/avatar/image?v=${encodeURIComponent(value.version)}`
  }
  async function refresh() {
    if (busy.value) return
    const turn = ++generation
    try {
      await ensureCreator()
      if (turn !== generation) return
      const owner = currentCreatorId()
      const result = await requestJson<AvatarResponse>('app/avatar')
      if (turn === generation && owner && owner === currentCreatorId()) avatarUrl.value = imageUrl(result)
    } catch { /* Keep the last confirmed avatar until the next successful read. */ }
  }
  async function upload(file: File) {
    if (busy.value) return false
    if (!['image/jpeg', 'image/png'].includes(file.type) || file.size === 0 || file.size > 5 * 1024 * 1024) {
      error.value = tr('请选择 5MB 以内的 JPG 或 PNG 图片。', 'Choose a JPG or PNG image up to 5 MB.'); return false
    }
    const owner = currentCreatorId()
    if (!owner) { error.value = tr('账号正在加载，请稍后再试。', 'Your account is loading. Please try again.'); return false }
    const turn = ++generation
    busy.value = true; error.value = ''
    try {
      await ensureCreator()
      if (turn !== generation || owner !== currentCreatorId()) return false
      const form = new FormData(); form.append('file', file); form.append('expectedCreatorId', owner)
      const result = await uploadForm<AvatarResponse>('app/avatar', form)
      if (turn !== generation || !owner || owner !== currentCreatorId()) return false
      avatarUrl.value = imageUrl(result)
      return true
    } catch { if (turn === generation) error.value = tr('头像上传失败，请检查图片后重试。', 'Could not upload your avatar. Check the image and try again.'); return false }
    finally { if (turn === generation) busy.value = false }
  }
  return { avatarUrl, busy, error, clear, refresh, upload }
}
