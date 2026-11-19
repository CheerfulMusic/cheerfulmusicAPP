import { onMounted, readonly, ref } from 'vue'
import { getPublicArtist, publicTrack, recordDiscoverPlay } from '../../artist/api/artist'
import type { PreviewTrack } from './tracks'
import { recordPlay } from './hot-songs'

// Reference the existing releases. Their current title, cover and public version come from the artist page.
export const houseSelections = [
  { artistId: '308036ef-8bef-486f-88cc-8266e5797768', songId: '0485811c-0297-4513-9fb1-75f981af8707', durationSeconds: 233.184 },
  { artistId: '308036ef-8bef-486f-88cc-8266e5797768', songId: '45e28036-c2bc-4f44-886c-3e40ddb32bf0', durationSeconds: 226.56 },
  { artistId: '308036ef-8bef-486f-88cc-8266e5797768', songId: 'd29a567d-988a-44fe-b5d8-c15ab625a23a', durationSeconds: 244.824 },
] as const

const houseTracks = ref<PreviewTrack[]>([])
let inflight: Promise<void> | undefined

export function loadHouseRecommendations(): Promise<void> {
  if (inflight) return inflight
  inflight = (async () => {
    const artists = new Set(houseSelections.map(selection => selection.artistId))
    const profiles = new Map(await Promise.all([...artists].map(async artistId => {
      try { return [artistId, await getPublicArtist(artistId)] as const }
      catch { return [artistId, undefined] as const }
    })))
    houseTracks.value = houseSelections.flatMap(selection => {
      const profile = profiles.get(selection.artistId)
      const song = profile?.songs.find(song => song.id === selection.songId)
      if (!profile || !song) return []
      return [{
        ...publicTrack(selection.artistId, song), authoredTitle: true,
        recipient: '', subtitle: profile.stageName, category: 'House', genre: 'House',
        filters: ['house', 'electronic'], tags: ['House'], style: 'House', occasion: '',
        description: '', quote: '', durationSeconds: selection.durationSeconds,
        publication: { artistId: selection.artistId, stageName: profile.stageName, song },
      }]
    })
  })().finally(() => { inflight = undefined })
  return inflight
}

export function useHouseRecommendations() {
  onMounted(() => { void loadHouseRecommendations() })
  return readonly(houseTracks)
}

const played = new Set<string>()
export async function recordPreviewPlay(track: PreviewTrack): Promise<void> {
  if (!track.publication) { recordPlay(track.id); return }
  const { artistId, song } = track.publication
  const key = `${artistId}/${song.id}/${song.version}`
  if (played.has(key)) return
  played.add(key)
  try { await recordDiscoverPlay(song) }
  catch { played.delete(key) }
}
