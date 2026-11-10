import {afterEach,expect,it,vi} from 'vitest'
import config from '../capacitor.config'
afterEach(()=>{vi.unstubAllGlobals();vi.unstubAllEnvs();vi.resetModules()})
it('native JSON and uploads use the native transport with credentials',async()=>{
 vi.resetModules()
 vi.doMock('../src/platform',()=>({platform:{kind:'ios'}}))
 vi.stubEnv('VITE_API_BASE_URL','https://cheerfulmusic.com/api')
 const fetch=vi.fn(async(url:string)=>new Response(JSON.stringify(url.endsWith('/csrf')?{token:'csrf'}:url.endsWith('/session')?{creatorId:'owner'}:{ok:true})))
 vi.stubGlobal('fetch',fetch)
 const {requestJson,uploadForm}=await import('../src/shared/http/api-client')
 await requestJson('audit/native')
 await uploadForm('audit/upload',new FormData())
 expect(fetch.mock.calls.every((call:any)=>call[1].credentials==='include')).toBe(true)
 expect(config.plugins?.CapacitorHttp?.enabled).toBe(true)
})
