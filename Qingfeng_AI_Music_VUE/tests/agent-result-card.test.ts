// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { assistantResult, resultMediaUrl, resultInviteUrl, uploadTarget, type AgentCommand } from '../src/modules/agent/model/assistant-result'
import AgentResultCard from '../src/modules/agent/components/AgentResultCard.vue'
import type { Message } from '../src/modules/agent/model/conversation'
const message = (body: unknown, role: Message['role'] = 'assistant'): Message => ({ id: 'm1', conversationId: 'c1', seq: 1, role, kind: 'TEXT', state: 'DONE', contentJson: JSON.stringify(body), taskId: null, createdAt: '' })
const body = { kind: 'works', text: '找到这些作品', items: [{ id: 'w1', title: '给妈妈的歌', subtitle: 'COMPLETED · 作品版本 0', data: { workId: 'w1' } }], commands: [{ kind: 'send', label: '打开「给妈妈的歌」', text: '打开作品 w1' }] }
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
    expect(wrapper.text()).not.toContain('COMPLETED · 作品版本 0')
    expect(wrapper.text()).not.toContain('workId')
    expect(wrapper.find('.agent-result-commands').exists()).toBe(false)
    await wrapper.find('.agent-work-open').trigger('click')
    expect(wrapper.emitted('command')).toEqual([[body.commands[0]]])
    wrapper.unmount()
  })
  it('matches each work by id even for duplicate titles and out-of-order commands, preserving other commands', async () => {
    const commands = [
      { kind: 'send', label: '打开第二首', text: '打开作品 w2' },
      { kind: 'send', label: '打开第一首', text: '打开作品 w1' },
      { kind: 'send', label: '下一页', text: '列出我的作品，第 2 页' },
    ]
    const result = assistantResult(message({ kind: 'works', items: [
      { id: 'w1', title: '同名作品', subtitle: 'COMPLETED · 作品版本 0', data: { workId: 'w1' } },
      { id: 'w2', title: '同名作品', subtitle: 'LYRICS_DRAFT · 作品版本 1', data: { workId: 'w2' } },
      { id: 'w3', title: '无命令作品', subtitle: 'READY_TO_GENERATE', data: { workId: 'w3' } },
    ], commands }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    const rows = wrapper.findAll('.agent-result-items li')
    expect(rows[0]!.find('.agent-work-open').attributes('aria-label')).toContain('同名作品')
    expect(rows[2]!.find('.agent-work-open').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/COMPLETED|LYRICS_DRAFT|READY_TO_GENERATE|作品版本/)
    await rows[0]!.find('.agent-work-open').trigger('click')
    await rows[1]!.find('.agent-work-open').trigger('click')
    expect(wrapper.emitted('command')).toEqual([[commands[1]], [commands[0]]])
    expect(wrapper.findAll('.agent-result-commands button').map(b => b.text())).toEqual(['下一页'])
    await wrapper.setProps({ disabled: true })
    expect(rows[0]!.find('.agent-work-open').attributes('disabled')).toBeDefined()
    await rows[0]!.find('.agent-work-open').trigger('click')
    expect(wrapper.emitted('command')).toHaveLength(2)
    wrapper.unmount()
  })
  it('shows the quoted lines and opens exactly their lyric revision', async () => {
    const command = { kind: 'send', label: '打开命中版本', text: '打开作品 w1 的歌词版本 r1' }
    const result = assistantResult(message({ kind: 'works', items: [{ id: 'w1', title: '海边来信', data: {
      workId: 'w1', revisionId: 'r1', lyricVersion: 1, matchSource: 'lyrics', excerpt: '上一句\n海风替我说晚安',
    } }], commands: [command] }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect(wrapper.find('blockquote').text()).toMatch(/V1/)
    expect(wrapper.find('blockquote p').text()).toBe('上一句\n海风替我说晚安')
    await wrapper.get('.agent-work-open').trigger('click')
    expect(wrapper.emitted('command')).toEqual([[command]])
    wrapper.unmount()
  })
  it('retains subtitles and actions on non-work result cards', async () => {
    const command = { kind: 'send', label: '选择版本', text: '选择第 1 版' }
    const result = assistantResult(message({ kind: 'versions', items: [{ id: 'r1', title: '歌词版本', subtitle: '已确认', data: {} }], commands: [command] }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect(wrapper.text()).toContain('已确认')
    expect(wrapper.find('.agent-work-open').exists()).toBe(false)
    await wrapper.find('.agent-result-commands button').trigger('click')
    expect(wrapper.emitted('command')).toEqual([[command]])
    wrapper.unmount()
  })
  it('keeps the description in a newly created work result', () => {
    const result = assistantResult(message({ kind: 'works', items: [{ id: 'new-work', title: '新作品', subtitle: '空白作品草稿', data: { workId: 'new-work' } }], commands: [] }))!
    const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
    expect(wrapper.text()).toContain('空白作品草稿')
    expect(wrapper.find('.agent-work-open').exists()).toBe(false)
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


it('Agent 示例音频失败后只回退一次，私人音频保持不变', async () => {
  const result = assistantResult(message({ kind: 'works', items: [
    { id: 's', title: '示例', data: { audioUrl: '/media/sample-red-guofeng-v1.mp3' } },
    { id: 'p', title: '私人', data: { audioUrl: '/api/app/works/w1/audio/a1' } }
  ] }))!
  const wrapper = mount(AgentResultCard, { props: { result, disabled: false, current: true } })
  const [sample, privateAudio] = wrapper.findAll('audio')
  await sample!.trigger('error')
  const fallback = (sample!.element as HTMLAudioElement).src
  expect(fallback).toContain('/media/sample-red-guofeng-v1.mp3?reauth=')
  await sample!.trigger('error')
  expect((sample!.element as HTMLAudioElement).src).toBe(fallback)
  const before = (privateAudio!.element as HTMLAudioElement).src
  await privateAudio!.trigger('error')
  expect((privateAudio!.element as HTMLAudioElement).src).toBe(before)
  wrapper.unmount()
})

it('keeps the complete capability catalogue beyond one hundred actions',()=>{
 const items=Array.from({length:120},(_,i)=>({id:'action'+i,title:'功能'+i,data:{}}))
 expect(assistantResult(message({kind:'capabilities',items}))?.items).toHaveLength(120)
 expect(assistantResult(message({kind:'works',items}))?.items).toHaveLength(100)
})
it('exports actual lyrics as a TXT blob without another generation request',async()=>{
 const create=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:lyrics'), revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{})
 const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
 const result=assistantResult(message({kind:'lyrics_download',items:[{id:'r',title:'歌名',data:{lyrics:'第一句\n第二句'}}]}))!
 const wrapper=mount(AgentResultCard,{props:{result,disabled:false,current:true}})
 await wrapper.find('button').trigger('click');expect(click).toHaveBeenCalledOnce()
 const blob=create.mock.calls[0]![0] as Blob;expect(await blob.text()).toContain('第一句\n第二句')
 wrapper.unmount();create.mockRestore();click.mockRestore();revoke.mockRestore()
})
