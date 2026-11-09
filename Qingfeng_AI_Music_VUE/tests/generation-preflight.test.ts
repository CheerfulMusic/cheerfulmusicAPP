import {expect,it} from 'vitest'
import {validResponse} from '../src/shared/http/response-contracts'

it('requires a complete generation preflight response within both budgets',()=>{
 const path='app/works/work/generations/preflight',valid={workVersion:0,revisionId:'revision',styleCharacters:900,styleLimit:1000,negativeCharacters:30,negativeLimit:500,notice:''}
 expect(validResponse(path,'POST',valid)).toBe(true)
 expect(validResponse(path,'POST',{...valid,styleCharacters:1001})).toBe(false)
 expect(validResponse(path,'POST',{...valid,negativeCharacters:501})).toBe(false)
 expect(validResponse(path,'POST',{revisionId:'revision'})).toBe(false)
})
it('rejects oversized AI suggestions and supports unavailable AI without blocking ordinary settings',()=>{
 expect(validResponse('app/music-settings/simplify','GET',{available:false})).toBe(true)
 expect(validResponse('app/music-settings/simplify','POST',{original:'original',text:'proposal'})).toBe(true)
 expect(validResponse('app/music-settings/simplify','POST',{original:'original',text:'x'.repeat(301)})).toBe(false)
})
