import {describe,it,expect} from 'vitest'
import {playlistDefinitions,filterPlaylists,type PreviewPlaylist} from '../src/modules/home/model/playlists'
import {tracks} from '../src/modules/home/model/tracks'

const playlists:PreviewPlaylist[]=playlistDefinitions.map(playlist=>({...playlist,tracks:playlist.trackIds.map(id=>tracks.find(track=>track.id===id)!)}))
describe('compact preview playlists',()=>{
 it('keeps all twenty-two supplied samples in their folder categories without duplicating existing songs',()=>{
  expect(playlists.map(playlist=>playlist.title)).toEqual(['华语流行','电子','欧美流行','House'])
  expect(playlists.map(playlist=>playlist.tracks.length)).toEqual([8,5,6,3])
  for(const playlist of playlists){expect(playlist.tracks.every(Boolean)).toBe(true)}
  const ids=playlists.flatMap(playlist=>playlist.tracks.map(track=>track.id))
  expect(new Set(ids).size).toBe(ids.length)
  expect([...ids].sort()).toEqual(tracks.filter(track=>track.delivery==='CDN').map(track=>track.id).sort())
 })
 it('filters new pop songs without duplicates or changing the full playlist',()=>{
  const before=JSON.stringify(playlists)
  const found=filterPlaylists(playlists,'pop')
  expect(found.map(playlist=>playlist.id)).toEqual(['mandopop','global-pop'])
  expect(new Set(found.flatMap(playlist=>playlist.tracks.map(track=>track.id))).size).toBe(14)
  expect(JSON.stringify(playlists)).toBe(before)
  expect(filterPlaylists(playlists,'all').flatMap(playlist=>playlist.tracks)).toHaveLength(22)
 })
 it('retains original audio references and returns only songs matching a genre',()=>{
  for(const genre of ['electronic']){
   const results=filterPlaylists(playlists,genre).flatMap(playlist=>playlist.tracks)
   expect(results).toHaveLength(8);expect(results.every(track=>track.filters.includes(genre))).toBe(true)
   expect(results[0]).toBe(tracks.find(track=>track.id===results[0]!.id))
  }
  expect(filterPlaylists(playlists,'unknown')).toEqual([])
  expect(filterPlaylists(playlists,'house').flatMap(playlist=>playlist.tracks).map(track=>track.title)).toEqual(['Body Language','Into the Sun','One More Night'])
  expect(filterPlaylists(playlists,'instrumental').flatMap(playlist=>playlist.tracks).map(track=>track.title)).toEqual(['Euphoric Drop'])
 })
 it('keeps authored titles and seventeen supplied lyrics without changing old track IDs',()=>{
  const supplied=tracks.filter(track=>track.delivery==='CDN')
  expect(supplied.every(track=>track.authoredTitle)).toBe(true)
  expect(supplied.filter(track=>track.lyrics)).toHaveLength(17)
  expect(tracks.find(track=>track.id==='family')!.audio).toBe('/media/sample-family-v2-preview-128.mp3')
 })
 it('includes the nine supplied lyric documents and keeps the instrumental free of invented lyrics',()=>{
  const added=tracks.filter(track=>track.audio.startsWith('/media/recommendations/'))
  expect(added).toHaveLength(10)
  expect(added.filter(track=>track.lyrics)).toHaveLength(9)
  expect(added.every(track=>track.authoredTitle&&track.delivery==='CDN'&&track.durationSeconds>120)).toBe(true)
  expect(added.find(track=>track.id==='into-the-sun')!.lyrics).toContain('Where the ocean meets the sky\n')
  expect(added.find(track=>track.id==='euphoric-drop')!.lyrics).toBeUndefined()
 })
})
