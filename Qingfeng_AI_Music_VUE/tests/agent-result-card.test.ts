// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { assistantResult, resultMediaUrl, resultInviteUrl, uploadTarget, type AgentCommand } from '../src/modules/agent/model/assistant-result'
import AgentResultCard from '../src/modules/agent/components/AgentResultCard.vue'
import type { Message } from '../src/modules/agent/model/conversation'
const message = (body: unknown, role: Message['role'] = 'assistant'): Message => ({ id: 'm1', conversationId: 'c1', seq: 1, role, kind: 'TEXT', state: 'DONE', contentJson: JSON.stringify(body), taskId: null, createdAt: '' })
const body = { kind: 'works', text: '找到这些作品', items: [{ id: 'w1', title: '给妈妈的歌', subtitle: '昨天 · 已生成', data: { workId: 'w1' } }], commands: [{ kind: 'send', label: '选择给妈妈的歌', text: '选择作品 w1' }] }
describe('conversation result cards', () => {
  it('shows a copyable invitation only for the exact first-party invitation route', () => {
    const origin = window.location.origin
    expect(resultInviteUrl('/account?invite=abc-12345', origin)).toBe(`${origin}/account?invite=abc-12345`)
    for (const path of ['https://evil.example/account?invite=abc123', '//evil.example/account?invite=abc123', '/account?invite=abc123&redirect=https://evil.example', '/other?invite=abc123']) expect(resultInviteUrl(path, origin)).toBe('')
    const result = assistantResult(message({ kind: 'referral', items: [{ id: 'invite', title: '邀请好友', data: { invitePath: '/account?invite=abc-12345' } }] }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe(`${origin}/account?invite=abc-12345`)
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })
  it('shows real records and sends exact service-provided selection commands', async () => {
    const result = assistantResult(message(body))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect(wrapper.text()).toContain('给妈妈的歌')
    expect(wrapper.text()).toContain('昨天 · 已生成')
    expect(wrapper.text()).not.toContain('workId')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('command')).toEqual([[body.commands[0]]])
    wrapper.unmount()
  })
  it('ignores user-authored result JSON, broken data and navigation commands', () => {
    expect(assistantResult(message(body, 'user'))).toBeNull()
    expect(assistantResult({ ...message(body), contentJson: '{broken' })).toBeNull()
    const result = assistantResult(message({ ...body, commands: [{ kind: 'navigate', label: 'Pay now', text: 'https://evil.example' }, { kind: 'send', label: 'bad', text: 9 }, body.commands[0]] }))!
    expect(result.commands).toEqual(body.commands)
  })
  it('disables stale confirmations and disables commands while an uncertain request is pending', async () => {
    const wrapper = mount(AgentResultCard, { props: { result: { ...assistantResult(message(body))!, kind: 'confirmation' }, disabled: false, current: false } })
    expect(wrapper.text()).toContain('这条确认已不是当前请求')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    await wrapper.find('button').trigger('click'); expect(wrapper.emitted('command')).toBeUndefined()
    await wrapper.setProps({ current: true, disabled: true })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('renders markup as text and accepts only fixed first-party media endpoints', () => {
    const origin = window.location.origin
    expect(resultMediaUrl('/api/app/works/w1/covers/c1', 'image', origin)).toBe(`${origin}/api/app/works/w1/covers/c1`)
    expect(resultMediaUrl('/media/sample.mp3', 'audio', origin)).toBe(`${origin}/media/sample.mp3`)
    for (const url of ['javascript:alert(1)', 'https://evil.example/media/song.mp3', '//evil.example/media/song.mp3', '/api/app/identity/recovery', '/media/../../account']) {
      expect(resultMediaUrl(url, 'audio', origin)).toBe('')
    }
    const result = assistantResult(message({ ...body, items: [{ id: 'w1', title: '<img src=x onerror=alert(1)>', data: { imageUrl: 'https://evil.example/a.png' } }] }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    wrapper.unmount()
  })
  it('scopes uploads to a fixed work id and never treats paths or URLs as upload targets', () => {
    const command = (kind: AgentCommand['kind'], text: string): AgentCommand => ({ kind, text, label: '选择文件' })
    expect(uploadTarget(command('upload_cover', 'w-1'))).toEqual({ kind: 'upload_cover', workId: 'w-1' })
    expect(uploadTarget(command('upload', 'audio:w-1'))).toEqual({ kind: 'upload_audio', workId: 'w-1' })
    expect(uploadTarget(command('upload_audio', '../account'))).toBeNull()
    expect(uploadTarget(command('upload', 'https://evil.example'))).toBeNull()
  })
})
