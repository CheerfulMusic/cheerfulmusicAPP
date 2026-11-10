import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({ ensureCreator: vi.fn(async () => undefined), requestJson: vi.fn() }))

vi.mock('../src/shared/http/api-client', () => ({
  ensureCreator: state.ensureCreator,
  requestJson: state.requestJson,
  ApiError: class ApiError extends Error {
    status: number
    code?: string
    constructor(message: string, status: number, code?: string) { super(message); this.status = status; this.code = code }
  },
}))

import { saveWork } from '../src/modules/studio/api/works'
import type { DraftInput } from '../src/modules/studio/api/works'

const DRAFT: DraftInput = {
  recipient: '妈妈', occasion: '生日', story: '一盏灯', message: '谢谢', style: '温暖民谣', title: '回家的灯', vocalGender: 'FEMALE',
}

beforeEach(() => { state.requestJson.mockReset(); state.ensureCreator.mockClear() })

describe('创作页保存载荷', () => {
  it('新建草稿时把人声一起提交，Agent 解析结果不会停在内存里', async () => {
    state.requestJson.mockResolvedValue({ ...DRAFT, id: 'w1', version: 0, createdAt: 'x', updatedAt: 'x' })
    await saveWork(DRAFT, undefined, 'draft-key')
    const [path, method, body] = state.requestJson.mock.calls[0]!
    expect(path).toBe('app/works')
    expect(method).toBe('POST')
    expect(body).toMatchObject({ vocalGender: 'FEMALE', draftKey: 'draft-key', style: '温暖民谣' })
    expect(state.ensureCreator).toHaveBeenCalled()
  })

  it('更新已有草稿时带上版本号与人声，实现「最后一次修改为准」', async () => {
    const male: DraftInput = { ...DRAFT, vocalGender: 'MALE' }
    state.requestJson.mockResolvedValue({ ...male, id: 'w1', version: 3, createdAt: 'x', updatedAt: 'x' })
    await saveWork(male, { id: 'w1', version: 2 })
    const [path, method, body] = state.requestJson.mock.calls[0]!
    expect(path).toBe('app/works/w1')
    expect(method).toBe('PUT')
    expect(body).toMatchObject({ vocalGender: 'MALE', expectedVersion: 2 })
  })

  it('后端尚未升级时明确报错，而不是静默丢掉人声', async () => {
    state.requestJson.mockResolvedValue({ ...DRAFT, vocalGender: undefined, id: 'w1', version: 0 })
    await expect(saveWork(DRAFT)).rejects.toMatchObject({ code: 'BACKEND_UPGRADE_REQUIRED' })
  })

  it('AUTO 不受旧后端影响', async () => {
    const auto: DraftInput = { ...DRAFT, vocalGender: 'AUTO' }
    state.requestJson.mockResolvedValue({ ...auto, vocalGender: undefined, id: 'w1', version: 0 })
    await expect(saveWork(auto)).resolves.toMatchObject({ id: 'w1' })
  })
})
