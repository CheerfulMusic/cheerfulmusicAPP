import {describe,it,expect} from 'vitest'
import {questionPart,restoredStoryPart,reachableStoryPart} from '../src/modules/studio/model/story-flow'

describe('一屏一问的故事恢复与导航',()=>{
  it('旧三页草稿落在对应的新问题，保留原有填写进度',()=>{
    expect([0,1,2].map(p=>restoredStoryPart(p))).toEqual([0,2,4])
    expect(restoredStoryPart(undefined)).toBe(0)
  })
  it('新版五屏进度可以原样恢复，越界和无效值不会访问不存在的问题',()=>{
    expect([0,1,2,3,4].map(p=>restoredStoryPart(p,2))).toEqual([0,1,2,3,4])
    expect(restoredStoryPart(100,2)).toBe(4)
    expect(restoredStoryPart(NaN,2)).toBe(0)
    expect(questionPart('story')).toBe(2)
    expect(questionPart(['story'])).toBeUndefined()
    expect(questionPart('unknown')).toBeUndefined()
  })
  it('缺少称呼或场景时返回该问题，有回忆或心意的后续校验不增加必填字段',()=>{
    expect(reachableStoryPart(4,{recipient:' ',occasion:''})).toBe(0)
    expect(reachableStoryPart(4,{recipient:'妈妈',occasion:''})).toBe(1)
    expect(reachableStoryPart(4,{recipient:'妈妈',occasion:'不为特别的日子'})).toBe(4)
  })
})
