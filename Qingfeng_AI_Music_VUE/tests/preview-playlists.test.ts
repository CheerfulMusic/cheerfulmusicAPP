import {describe,it,expect} from 'vitest'
import {playlistDefinitions,filterPlaylists,type PreviewPlaylist} from '../src/modules/home/model/playlists'
import {tracks} from '../src/modules/home/model/tracks'

const playlists:PreviewPlaylist[]=playlistDefinitions.map(playlist=>({...playlist,tracks:playlist.trackIds.map(id=>tracks.find(track=>track.id===id)!)}))
describe('compact preview playlists',()=>{
 it('includes each available song once across three lists with three songs each',()=>{
  expect(playlists).toHaveLength(3)
  for(const playlist of playlists){expect(playlist.tracks).toHaveLength(3);expect(playlist.tracks.every(Boolean)).toBe(true)}
  const ids=playlists.flatMap(playlist=>playlist.tracks.map(track=>track.id))
  expect(new Set(ids).size).toBe(ids.length)
  expect([...ids].sort()).toEqual(tracks.map(track=>track.id).sort())
 })
 it('finds the shared family/pop recording without duplicates or changing the full playlist',()=>{
  const before=JSON.stringify(playlists)
  for(const filter of ['pop','family']){
   const found=filterPlaylists(playlists,filter)
   expect(found).toHaveLength(1);expect(found[0]!.tracks.map(track=>track.id)).toEqual(['family'])
   expect(found[0]!.id).toBe('heart')
  }
  expect(JSON.stringify(playlists)).toBe(before)
  expect(filterPlaylists(playlists,'all').flatMap(playlist=>playlist.tracks)).toHaveLength(9)
 })
 it('retains original audio references and returns only songs matching a hidden genre',()=>{
  for(const genre of ['guofeng','disco','rock','folk','electronic','instrumental']){
   const results=filterPlaylists(playlists,genre).flatMap(playlist=>playlist.tracks)
   expect(results).toHaveLength(1);expect(results[0]!.filters).toContain(genre)
   expect(results[0]).toBe(tracks.find(track=>track.id===results[0]!.id))
  }
  expect(filterPlaylists(playlists,'unknown')).toEqual([])
 })
})
