import { onBeforeUnmount, ref } from 'vue'
import { currentCreatorId, ensureCreator } from '../../../shared/http/api-client'
import { getArtistProfile, ownerAvatarUrl } from '../../artist/api/artist'

export function useProfileAvatar() {
  const imageUrl = ref('')
  let revision = 0
  function clear() { revision++; imageUrl.value = '' }
  async function refresh() {
    const turn = ++revision
    try {
      await ensureCreator()
      if (turn !== revision) return
      const owner = currentCreatorId()
      const profile = await getArtistProfile()
      if (owner && owner === currentCreatorId() && turn === revision)
        imageUrl.value = profile.avatarKey ? ownerAvatarUrl(profile.avatarKey) : ''
    } catch { /* The account avatar remains the fallback if the profile cannot be read. */ }
  }
  onBeforeUnmount(clear)
  return { imageUrl, refresh, clear }
}
