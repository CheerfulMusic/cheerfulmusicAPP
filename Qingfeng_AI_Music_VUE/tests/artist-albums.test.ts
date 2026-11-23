// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AlbumManager from '../src/modules/artist/components/AlbumManager.vue'
import type { ArtistProfile } from '../src/modules/artist/api/artist'
import { ownAlbums, saveAlbum, type Album } from '../src/modules/artist/api/albums'
import { emptyResponseAllowed, validResponse } from '../src/shared/http/response-contracts'

vi.mock('../src/modules/artist/api/albums', async original => ({
  ...await original<typeof import('../src/modules/artist/api/albums')>(), ownAlbums: vi.fn(), saveAlbum: vi.fn(),
}))
const songs = ['海岸','星河'].map((title,index)=>({
  id:'song-'+index,title,version:1,workId:'work-'+index,assetId:'asset-'+index,coverId:null,
}))
const profile: ArtistProfile={ publicId:'artist',stageName:'青风',tagline:'',avatarKey:null,coverKey:null,featuredReleaseId:null,published:true,songs }
const draft: Album={id:'album',title:'旧名',description:'',coverKey:null,published:false,version:2,
  tracks:songs.map(song=>({...song,hasCover:false}))}
beforeEach(()=>{vi.mocked(ownAlbums).mockResolvedValue([draft])})
afterEach(()=>vi.clearAllMocks())

describe('creator album editor',()=>{
  it('loads on demand, saves an explicitly published track order, and preserves the draft on an upload error',async()=>{
    const view=mount(AlbumManager,{props:{profile},global:{stubs:{RouterLink:true}}})
    const detail=view.get('details')
    detail.element.open=true
    await detail.trigger('toggle')
    await flushPromises()
    const click=async(label:string)=>view.findAll('button').find(button=>button.text()===label)!.trigger('click')
    await click('编辑')
    await view.get('input[required]').setValue('新的专辑')
    await click('下移')
    vi.mocked(saveAlbum).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({...draft,title:'新的专辑',published:true,version:3})
    await click('公开专辑')
    await flushPromises()
    expect(view.find('form').exists()).toBe(true)
    expect(view.get<HTMLInputElement>('input[required]').element.value).toBe('新的专辑')
    await click('公开专辑')
    await flushPromises()
    const body=vi.mocked(saveAlbum).mock.calls[1]![1]
    expect(body.getAll('tracks')).toEqual(['song-1','song-0'])
    expect(body.getAll('versions')).toEqual(['1','1'])
    expect([body.get('version'),body.get('published')]).toEqual(['2','true'])
    expect(view.find('form').exists()).toBe(false)
    view.unmount()
  })
  it('accepts empty album catalogues and a successful version-checked delete',()=>{
    expect(validResponse('public/artists/artist/albums','GET',[])).toBe(true)
    expect(validResponse('app/artist-albums','GET',[draft])).toBe(true)
    expect(validResponse('app/artist-albums','GET',[{...draft,tracks:[{id:'hidden'}]}])).toBe(false)
    expect(emptyResponseAllowed('app/artist-albums/album?version=2','DELETE')).toBe(true)
    expect(validResponse('app/artist-albums/album?version=2','DELETE',undefined)).toBe(true)
  })
})
