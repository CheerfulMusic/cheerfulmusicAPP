import {describe,it,expect} from 'vitest'
import {playlistDefinitions,filterPlaylists,type PreviewPlaylist} from '../src/modules/home/model/playlists'
import {tracks} from '../src/modules/home/model/tracks'

const playlists:PreviewPlaylist[]=playlistDefinitions.map(playlist=>({...playlist,tracks:playlist.trackIds.map(id=>tracks.find(track=>track.id===id)!)}))
describe('compact preview playlists',()=>{
 it('keeps the existing recommendations and restores each sample to its requested playlist once',()=>{
  expect(playlists.map(playlist=>playlist.title)).toEqual(['华语流行','电子','欧美流行','Hip-Hop&R&B'])
  expect(playlists.map(playlist=>playlist.tracks.length)).toEqual([14,11,6,6])
  expect(playlists[0]!.tracks.slice(-6).map(track=>track.id)).toEqual(['love','red-guofeng','family','travel','river-instrumental','scenery-folk'])
  expect(playlists[1]!.tracks.slice(-6).map(track=>track.id)).toEqual(['hometown-electronic','rain-disco','tomorrow-rock','body-language','into-the-sun','one-more-night'])
  expect(playlists[3]!.tracks.map(track=>track.id)).toEqual(['golden-hour-love','no-reply','too-close','big-motion','running-through-my-mind','westside-summer'])
  for(const playlist of playlists){expect(playlist.tracks.every(Boolean)).toBe(true)}
  const ids=playlists.flatMap(playlist=>playlist.tracks.map(track=>track.id))
  expect(new Set(ids).size).toBe(ids.length)
  expect([...ids].sort()).toEqual(tracks.filter(track=>track.delivery==='CDN').map(track=>track.id).sort())
 })
 it('filters new pop songs without duplicates or changing the full playlist',()=>{
  const before=JSON.stringify(playlists)
  const found=filterPlaylists(playlists,'pop')
  expect(found.map(playlist=>playlist.id)).toEqual(['mandopop','global-pop'])
  expect(new Set(found.flatMap(playlist=>playlist.tracks.map(track=>track.id))).size).toBe(20)
  expect(JSON.stringify(playlists)).toBe(before)
  expect(filterPlaylists(playlists,'all').flatMap(playlist=>playlist.tracks)).toHaveLength(37)
 })
 it('retains original audio references and returns only songs matching a genre',()=>{
  for(const genre of ['electronic']){
   const results=filterPlaylists(playlists,genre).flatMap(playlist=>playlist.tracks)
   expect(results).toHaveLength(11);expect(results.every(track=>track.filters.includes(genre))).toBe(true)
   expect(results[0]).toBe(tracks.find(track=>track.id===results[0]!.id))
  }
  expect(filterPlaylists(playlists,'rnb').flatMap(playlist=>playlist.tracks).map(track=>track.id)).toEqual(['golden-hour-love','no-reply','too-close'])
  expect(filterPlaylists(playlists,'hiphop').flatMap(playlist=>playlist.tracks).map(track=>track.id)).toEqual(['big-motion','running-through-my-mind','westside-summer'])
  expect(filterPlaylists(playlists,'unknown')).toEqual([])
  expect(filterPlaylists(playlists,'house').flatMap(playlist=>playlist.tracks).map(track=>track.title)).toEqual(['Body Language','Into the Sun','One More Night'])
  expect(filterPlaylists(playlists,'instrumental').flatMap(playlist=>playlist.tracks).map(track=>track.title)).toEqual(['When the River Breathes','Euphoric Drop'])
 })
 it('keeps authored titles and the supplied lyrics without changing old track IDs',()=>{
  const supplied=tracks.slice(9)
  expect(supplied.every(track=>track.authoredTitle)).toBe(true)
  expect(supplied.filter(track=>track.lyrics)).toHaveLength(23)
  expect(tracks.find(track=>track.id==='family')!.audio).toBe('/media/sample-family-v2-preview-128.mp3')
 })
 it('includes the supplied lyric documents and keeps the instrumental free of invented lyrics',()=>{
  const added=tracks.filter(track=>track.audio.startsWith('/media/recommendations/'))
  expect(added).toHaveLength(16)
  expect(added.filter(track=>track.lyrics)).toHaveLength(15)
  expect(added.every(track=>track.authoredTitle&&track.delivery==='CDN'&&track.durationSeconds>120)).toBe(true)
  expect(added.find(track=>track.id==='into-the-sun')!.lyrics).toContain('Where the ocean meets the sky\n')
  expect(added.find(track=>track.id==='no-reply')!.lyrics).toContain('But there')
  expect(added.find(track=>track.id==='westside-summer')!.lyrics).toContain('Westside summer, we outside')
  expect(added.find(track=>track.id==='euphoric-drop')!.lyrics).toBeUndefined()
 })
})
