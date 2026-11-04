import {expect,it} from 'vitest'
import {normalizeVocalGender} from '../src/modules/studio/model/vocals'
it('restores old drafts as automatic while preserving explicit male and female choices',()=>{expect(normalizeVocalGender(undefined)).toBe('AUTO');expect(normalizeVocalGender(null)).toBe('AUTO');expect(normalizeVocalGender('MALE')).toBe('MALE');expect(normalizeVocalGender('FEMALE')).toBe('FEMALE');expect(normalizeVocalGender('invalid')).toBe('AUTO')})
