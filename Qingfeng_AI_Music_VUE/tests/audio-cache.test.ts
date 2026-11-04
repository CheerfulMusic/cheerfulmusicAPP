import {describe,it,expect,vi} from 'vitest'
import {NativeAudioPool} from '../src/shared/player/audio-cache'
const media=()=>({pause:vi.fn(),removeAttribute:vi.fn(),load:vi.fn()}) as unknown as HTMLAudioElement

describe('原生音频实例池',()=>{
 it('相同地址复用实例，不再次设置src或load',()=>{const create=vi.fn(media),pool=new NativeAudioPool(create);const first=pool.acquire('/one');expect(pool.acquire('/one')).toBe(first);expect(create).toHaveBeenCalledTimes(1);expect(first.load).not.toHaveBeenCalled()})
 it('保留最近两首，切回原版仍是原实例',()=>{const pool=new NativeAudioPool(media);const a=pool.acquire('/one'),b=pool.acquire('/two');expect(pool.acquire('/one')).toBe(a);pool.acquire('/three');expect(a.load).not.toHaveBeenCalled();expect(b.pause).toHaveBeenCalled();expect(b.removeAttribute).toHaveBeenCalledWith('src');expect(b.load).toHaveBeenCalledTimes(1)})
 it('身份重置释放全部媒体，之后重新领取新实例',()=>{const pool=new NativeAudioPool(media),a=pool.acquire('/one'),b=pool.acquire('/two');pool.clear();expect(a.removeAttribute).toHaveBeenCalledWith('src');expect(b.removeAttribute).toHaveBeenCalledWith('src');expect(pool.acquire('/one')).not.toBe(a)})
 it('不同授权地址不混用，即使它们可能指向同一音频',()=>{const pool=new NativeAudioPool(media);expect(pool.acquire('/api/app/audio/a')).not.toBe(pool.acquire('/api/public/gifts/token/audio'))})
})
