import {afterEach,expect,it} from 'vitest'
import {mkdtempSync,mkdirSync,writeFileSync,rmSync,readFileSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {previewMediaManifest} from '../build/preview-media'
import {createHash} from 'node:crypto'
import viteConfig from '../vite.config'
const directories:string[]=[]
it('routes recommendation audio and covers through the normal development entry',async()=>{
 const config=await viteConfig({command:'serve',mode:'test'})
 const rules=Object.keys(config.server!.proxy!).filter(rule=>rule.startsWith('^')).map(rule=>new RegExp(rule))
 for(const path of ['/media/sample-feng-ye-xing-v1-preview-128.mp3','/media/sample-feng-ye-xing-v1-cover.jpg','/media/sample-feng-ye-xing-v1-preview-128.mp3?reauth=1',
   '/media/recommendations/body-language-8e59749c37e9-preview-128.mp3','/media/recommendations/body-language-c251e80045fe-cover.jpg',
   '/media/recommendations/body-language-8e59749c37e9-preview-128.mp3?reauth=1']){
  expect(rules.some(rule=>rule.test(path)),path).toBe(true)
 }
 expect(rules.some(rule=>rule.test('/media/trending-genres-v1.png'))).toBe(false)
 for(const path of ['/media/recommendations/private.mp3','/media/recommendations/body-language-8e59749c37e9-preview-128.mp3/private','/media/recommendations/../private.mp3']){
  expect(rules.some(rule=>rule.test(path)),path).toBe(false)
 }
})
afterEach(()=>{for(const d of directories.splice(0))rmSync(d,{recursive:true,force:true})})
function fixture(audio:Buffer|null){
 const root=mkdtempSync(resolve(tmpdir(),'preview-media-'));directories.push(root)
 mkdirSync(resolve(root,'src/modules/home/model'),{recursive:true});mkdirSync(resolve(root,'public/media'),{recursive:true})
 writeFileSync(resolve(root,'src/modules/home/model/tracks.ts'),"export const tracks=[{audio:'/media/sample.mp3',cover:'/media/cover.svg'}]")
 writeFileSync(resolve(root,'public/media/cover.svg'),'<svg/>')
 if(audio)writeFileSync(resolve(root,'public/media/sample.mp3'),audio)
 return root
}
it('verifies all local and CDN preview assets and produces byte/hash metadata',()=>{
 const manifest=previewMediaManifest(fileURLToPath(new URL('..',import.meta.url)))
 expect(manifest.assets.filter(a=>a.path.endsWith('.mp3'))).toHaveLength(9)
 expect(manifest.assets.every(a=>a.bytes>0&&/^[a-f0-9]{64}$/.test(a.sha256))).toBe(true)
 expect(manifest.remoteAssets).toHaveLength(44)
 expect(manifest.remoteAssets.every(a=>a.bytes>0&&a.key.includes(a.sha256.slice(0,12)))).toBe(true)
})
it('blocks builds when sparse checkout omits audio',()=>{
 expect(()=>previewMediaManifest(fixture(null))).toThrow('Missing preview asset: /media/sample.mp3')
})
it.each([Buffer.alloc(0),Buffer.from('<html>404</html>')])('rejects empty or non-audio content',bytes=>{
 expect(()=>previewMediaManifest(fixture(bytes))).toThrow(/Empty preview|Invalid MP3/)
})
it('does not substitute or re-encode original audio',()=>{
 const root=fixture(Buffer.from('ID3original-audio')),manifest=previewMediaManifest(root)
 expect(manifest.assets.find(a=>a.path.endsWith('.mp3'))?.bytes).toBe(17)
 expect(readFileSync(resolve(root,'public/media/sample.mp3')).toString()).toBe('ID3original-audio')
})
it('rejects a CDN track without its checked object manifest',()=>{
 const root=fixture(Buffer.from('ID3original-audio'))
 writeFileSync(resolve(root,'src/modules/home/model/tracks.ts'),`export const tracks=[{id:'new-song',delivery:'CDN',audio:'/media/sample-new-song-v1-preview-128.mp3',cover:'/media/sample-new-song-v1-cover.jpg'}]`)
 expect(()=>previewMediaManifest(root)).toThrow('Invalid CDN preview asset')
})
it('accepts hash-named CDN files and rejects a changed local fallback',()=>{
 const root=fixture(null),entries=[]
 for(const [kind,bytes,suffix] of [['audio',Buffer.from('ID3original-audio'),'preview-128.mp3'],['cover',Buffer.from('image-bytes'),'cover.jpg']] as const){
  const sha256=createHash('sha256').update(bytes).digest('hex'),hash=sha256.slice(0,12)
  const name=`new-song-${hash}-${suffix}`,path=`/media/recommendations/${name}`
  mkdirSync(resolve(root,'public/media/recommendations'),{recursive:true});writeFileSync(resolve(root,'public','.'+path),bytes)
  entries.push({trackId:'new-song',kind,path,sha256,bytes:bytes.length,key:`qingfeng/recommendation-samples/20261119/${kind}/${hash}/${name}`})
 }
 writeFileSync(resolve(root,'public/recommendation-assets.json'),JSON.stringify({assets:entries}))
 writeFileSync(resolve(root,'src/modules/home/model/tracks.ts'),`export const tracks=[{id:'new-song',delivery:'CDN',audio:'${entries[0]!.path}',cover:'${entries[1]!.path}'}]`)
 expect(previewMediaManifest(root).remoteAssets).toHaveLength(2)
 writeFileSync(resolve(root,'public','.'+entries[0]!.path),'ID3modified-audio')
 expect(()=>previewMediaManifest(root)).toThrow('CDN fallback asset differs')
})
