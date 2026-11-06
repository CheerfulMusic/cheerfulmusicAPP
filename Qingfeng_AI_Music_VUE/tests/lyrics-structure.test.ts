import {expect,it,vi,afterEach} from 'vitest'
import {lyricTemplates,lyricSections,normalizeLyricsStructure,lyricsStructureKey} from '../src/modules/works/model/lyrics-structure'
import {continuationRequestKey} from '../src/modules/studio/model/lyrics-continuation'
import {resetCreatorSession} from '../src/shared/http/api-client'
import {generateLyrics} from '../src/modules/works/api/detail'

afterEach(()=>{resetCreatorSession();vi.unstubAllGlobals()})
it('keeps all four exact orders and moves rather than duplicates the first chorus',()=>{
  expect(lyricTemplates.AAB.join('|')).toBe('Verse 1|Verse 2|Chorus|Interlude|Verse 2|Chorus|Chorus 2')
  expect(lyricTemplates.ABB.join('|')).toBe('Verse|Chorus|Chorus 2|Interlude|Verse|Chorus|Chorus 2')
  expect(lyricTemplates.ABC.join('|')).toBe('Verse|Pre-Chorus|Chorus|Interlude|Pre-Chorus|Chorus|Chorus 2')
  expect(lyricTemplates.ABCD.join('|')).toBe('Verse|Pre-Chorus|Chorus|Interlude|Pre-Chorus|Chorus|Bridge|Chorus')
  for(const templateId of Object.keys(lyricTemplates) as (keyof typeof lyricTemplates)[]){
    const before=lyricSections({templateId,chorusFirst:false}),moved=[...before]
    moved.splice(moved.indexOf('Chorus'),1);moved.unshift('Chorus')
    expect(lyricSections({templateId,chorusFirst:true})).toEqual(moved)
  }
  expect(normalizeLyricsStructure({templateId:'invalid',chorusFirst:true})).toEqual({templateId:'AUTO',chorusFirst:false})
})
it('separates paid request keys when the template or chorus-first selection changes',()=>{
  const work=crypto.randomUUID(),a={templateId:'ABC',chorusFirst:false} as const,b={...a,chorusFirst:true}
  const first=continuationRequestKey(work,0,'mixed',lyricsStructureKey(a))
  expect(continuationRequestKey(work,0,'mixed',lyricsStructureKey(a))).toBe(first)
  expect(continuationRequestKey(work,0,'mixed',lyricsStructureKey(b))).not.toBe(first)
  expect(continuationRequestKey(work,0,'mixed','ABCD:normal')).not.toBe(first)
})
it('sends structural choices as distinct API fields together with language and revision requirements',async()=>{
  const fetch=vi.fn(async (url:unknown)=>Response.json(String(url).endsWith('/csrf')?{token:'csrf'}:String(url).endsWith('/session')?{creatorId:'owner'}:{id:'revision',workId:'work',workVersion:0,title:'song',lyrics:'lyrics',confirmed:false}))
  vi.stubGlobal('fetch',fetch)
  await generateLyrics('work',0,crypto.randomUUID(),'mixed','保留原话','base',{templateId:'ABCD',chorusFirst:true})
  const call=fetch.mock.calls.find(([url])=>String(url).endsWith('/generate'))!
  const init=(call as unknown as [string,RequestInit])[1]
  expect(JSON.parse(String(init.body))).toMatchObject({language:'mixed',instructions:'保留原话',baseRevisionId:'base',templateId:'ABCD',chorusFirst:true})
})
