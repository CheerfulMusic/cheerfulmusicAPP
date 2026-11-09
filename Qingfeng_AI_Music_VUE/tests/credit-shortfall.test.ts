import {expect,it} from 'vitest'
import {creditGap,isCreditShortfall} from '../src/modules/works/model/credit-shortfall'
import {ApiError} from '../src/shared/http/api-client'
it('treats only the confirmed quota rejection as a credit shortfall',()=>{
  expect(isCreditShortfall(new ApiError('积分不足，请先充值。',402,'INSUFFICIENT_CREDITS'))).toBe(true)
  expect(isCreditShortfall(new ApiError('请求超时，请检查网络后重试；不要连续重复提交。',408))).toBe(false)
  expect(isCreditShortfall(new ApiError('服务响应不完整，操作结果尚未确认，请先刷新核对，不要重复提交。',0,'INVALID_RESPONSE'))).toBe(false)
  expect(isCreditShortfall(new Error('积分不足，请先充值。'))).toBe(false)
  expect(isCreditShortfall(undefined)).toBe(false)
})
it('shows numbers only when the real balance is known',()=>{
  expect(creditGap({enabled:true,balance:0,musicCost:15})).toEqual({cost:15,balance:0,missing:15})
  expect(creditGap({enabled:true,balance:10,musicCost:15})?.missing).toBe(5)
  expect(creditGap({enabled:true,balance:20,musicCost:15})?.missing).toBe(0)
  expect(creditGap({enabled:false,balance:0,musicCost:15})).toBeUndefined()
  expect(creditGap({enabled:true,balance:0,musicCost:0})).toBeUndefined()
  expect(creditGap(undefined)).toBeUndefined()
})
