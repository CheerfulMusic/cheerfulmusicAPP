// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({ensure:vi.fn(),request:vi.fn(),login:vi.fn()}))
vi.mock('../src/shared/http/api-client',()=>({ensureCreator:api.ensure,requestJson:api.request,ApiError:class ApiError extends Error {constructor(message:string,public status:number,public code?:string){super(message)}}}))
vi.mock('../src/shared/identity/login-dialog',()=>({showLoginDialog:api.login}))
vi.mock('../src/shared/identity/credit-balance',()=>({loadCredits:vi.fn(async()=>undefined)}))
import { downloadSong } from '../src/modules/works/api/download'
import { ApiError } from '../src/shared/http/api-client'
describe('download unlock',()=>{
 beforeEach(()=>{vi.restoreAllMocks();vi.resetAllMocks();api.ensure.mockResolvedValue(undefined);Object.defineProperty(window,'confirm',{value:vi.fn(),configurable:true});Object.defineProperty(window,'alert',{value:vi.fn(),configurable:true})})
 it('confirms first charge and starts download after unlock',async()=>{
  api.request.mockResolvedValueOnce({unlocked:false,price:10}).mockResolvedValueOnce({unlocked:true,price:10})
  vi.spyOn(window,'confirm').mockReturnValue(true);const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  expect(await downloadSong('app/works/w/audio/a/download','/song?download=true','song.mp3')).toBe(true)
  expect(api.request).toHaveBeenNthCalledWith(2,'app/works/w/audio/a/download','POST',{})
  expect(click).toHaveBeenCalledOnce()
 })
 it('downloads an unlocked work without charging again',async()=>{
  api.request.mockResolvedValueOnce({unlocked:true,price:10});const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  expect(await downloadSong('path','/song','song.mp3')).toBe(true)
  expect(api.request).toHaveBeenCalledOnce();expect(click).toHaveBeenCalledOnce()
 })
 it('authorizes a newly shared version for free when the work was already purchased',async()=>{
  api.request.mockResolvedValueOnce({unlocked:false,price:0}).mockResolvedValueOnce({unlocked:true,price:0})
  const confirm=vi.spyOn(window,'confirm');const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  expect(await downloadSong('path','/song','song.mp3')).toBe(true)
  expect(confirm).not.toHaveBeenCalled();expect(api.request).toHaveBeenNthCalledWith(2,'path','POST',{});expect(click).toHaveBeenCalledOnce()
 })
 it('does not download after cancelled confirmation or insufficient credits',async()=>{
  api.request.mockResolvedValueOnce({unlocked:false,price:10});vi.spyOn(window,'confirm').mockReturnValue(false)
  const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  expect(await downloadSong('path','/song','song.mp3')).toBe(false);expect(api.request).toHaveBeenCalledOnce();expect(click).not.toHaveBeenCalled()
  api.request.mockResolvedValueOnce({unlocked:false,price:10}).mockRejectedValueOnce(new ApiError('积分不足',400,'INSUFFICIENT_CREDITS'))
  vi.spyOn(window,'confirm').mockReturnValue(true);vi.spyOn(window,'alert').mockImplementation(()=>{})
  expect(await downloadSong('path','/song','song.mp3')).toBe(false);expect(click).not.toHaveBeenCalled()
 })
 it('offers login for guest and never downloads',async()=>{
  api.request.mockRejectedValueOnce(new ApiError('请先登录',403,'DOWNLOAD_LOGIN_REQUIRED'))
  const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
  expect(await downloadSong('path','/song','song.mp3')).toBe(false);expect(api.login).toHaveBeenCalledOnce();expect(click).not.toHaveBeenCalled()
 })
})
