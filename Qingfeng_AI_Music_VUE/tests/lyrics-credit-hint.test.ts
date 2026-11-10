// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CreditCostHint from '../src/shared/credits/CreditCostHint.vue'
import { english } from '../src/shared/i18n/en'

const stubs = { RouterLink: { template: '<a><slot /></a>' } }

function hint(props: Record<string, unknown>) {
  return mount(CreditCostHint, { props: { cost: 5, balance: 20, ...props }, global: { stubs } })
}

describe('歌词生成的积分消耗提示', () => {
  it('显示后端下发的单次消耗积分', () => {
    const wrapper = hint({})
    expect(wrapper.text()).toContain('本次生成消耗 5 积分')
    expect(wrapper.text()).not.toContain('积分不足')
    expect(wrapper.find('.credit-cost-icon').exists()).toBe(true)
  })

  it('价格变化时提示跟随后端，不写死在前端', () => {
    const wrapper = hint({ cost: 9 })
    expect(wrapper.text()).toContain('本次生成消耗 9 积分')
  })

  it('余额不足时给出余额与所需积分', () => {
    const wrapper = hint({ balance: 2 })
    expect(wrapper.text()).toContain('积分不足，当前余额 2 积分，生成需要 5 积分。')
    expect(wrapper.find('.credit-cost').classes()).toContain('short')
  })

  it('余额刚好等于价格时不提示不足', () => {
    const wrapper = hint({ balance: 5 })
    expect(wrapper.text()).toContain('本次生成消耗 5 积分')
    expect(wrapper.text()).not.toContain('积分不足')
    expect(wrapper.find('.credit-cost').classes()).not.toContain('short')
  })

  it('余额读不到时不编造数字', () => {
    const wrapper = hint({ balance: null })
    expect(wrapper.text()).toContain('本次生成消耗 5 积分')
    expect(wrapper.text()).not.toContain('积分不足')
    expect(wrapper.text()).not.toContain('当前余额')
  })

  it('点击可查看积分规则，再次点击收起', async () => {
    const wrapper = hint({})
    expect(wrapper.find('.credit-rule').exists()).toBe(false)
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.find('.credit-rule').exists()).toBe(true)
    expect(wrapper.text()).toContain('积分规则')
    expect(wrapper.text()).toContain('歌词生成成功后保留积分扣费。')
    expect(wrapper.text()).toContain('生成失败、超时或取消将自动退还积分。')
    expect(wrapper.text()).toContain('当前余额 20 积分，单次生成消耗 5 积分。')
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.find('.credit-rule').exists()).toBe(false)
  })

  it('积分不足时提供充值入口', async () => {
    const wrapper = hint({ balance: 1 })
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.find('.credit-rule-link').exists()).toBe(true)
    expect(wrapper.find('.credit-rule-link').text()).toContain('去充值')
  })

  it('生成中标记忙碌状态', () => {
    const wrapper = hint({ busy: true })
    expect(wrapper.find('.credit-cost-trigger').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('.credit-cost').classes()).toContain('busy')
  })

  it('提示是低视觉权重的小字说明，不是主操作', () => {
    const wrapper = hint({})
    expect(wrapper.find('button.credit-cost-trigger').exists()).toBe(true)
    expect(wrapper.find('.credit-cost-text').text()).toBe('本次生成消耗 5 积分')
  })

  it('重新生成时用同一套积分表达说明价格', () => {
    const wrapper = hint({ variant: 'regenerate' })
    expect(wrapper.text()).toContain('重新生成歌词同样消耗 5 积分')
    expect(wrapper.text()).not.toContain('本次生成消耗')
  })

  it('重新生成积分不足时仍给出统一的不足提示', () => {
    const wrapper = hint({ variant: 'regenerate', balance: 1 })
    expect(wrapper.text()).toContain('积分不足，当前余额 1 积分，生成需要 5 积分。')
  })

  it('积分规则说明与实际扣费口径一致', async () => {
    const wrapper = hint({})
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.text()).toContain('歌词生成成功后保留积分扣费。')
    expect(wrapper.text()).toContain('生成失败、超时或取消将自动退还积分。')
  })

  it('不再出现与收费机制冲突的次数额度文案', () => {
    const wrapper = hint({})
    expect(wrapper.text()).not.toContain('不限次数')
    expect(wrapper.text()).not.toContain('次歌词')
    expect(wrapper.text()).not.toContain('创作额度')
  })

  it('改写在免费额度内说明不消耗积分并给出剩余次数', () => {
    const wrapper = hint({ variant: 'rewrite', cost: 1, balance: 0, freeRemaining: 3 })
    expect(wrapper.text()).toContain('本次改写不消耗积分，今日免费改写还剩 3 次')
    expect(wrapper.text()).not.toContain('积分不足')
  })

  it('改写用完免费额度后显示单次消耗', () => {
    const wrapper = hint({ variant: 'rewrite', cost: 1, balance: 20, freeRemaining: 0 })
    expect(wrapper.text()).toContain('本次改写消耗 1 积分')
  })

  it('改写积分不足时给出余额与所需积分', () => {
    const wrapper = hint({ variant: 'rewrite', cost: 1, balance: 0, freeRemaining: 0 })
    expect(wrapper.text()).toContain('积分不足，当前余额 0 积分，改写需要 1 积分。')
    expect(wrapper.find('.credit-cost').classes()).toContain('short')
  })

  it('改写规则说明免费次数按调用计算', async () => {
    const wrapper = hint({ variant: 'rewrite', cost: 1, balance: 20, freeRemaining: 2 })
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.text()).toContain('今日免费改写还剩 2 次，用完后每次消耗 1 积分。')
    expect(wrapper.text()).toContain('改写失败会退还已扣积分；免费次数按调用计算，失败同样占用一次。')
  })

  it('永久免费时不显示剩余次数，也不显示超大数字', async () => {
    const wrapper = hint({ variant: 'rewrite', cost: 1, balance: 0, freeRemaining: -1 })
    expect(wrapper.text()).toContain('本次改写不消耗积分')
    expect(wrapper.text()).not.toContain('本次改写消耗')
    expect(wrapper.text()).not.toContain('9223372036854775807')
    await wrapper.find('.credit-cost-trigger').trigger('click')
    expect(wrapper.text()).toContain('AI 改写当前不消耗积分。')
  })
})

describe('积分文案的中英文一致性', () => {
  it('界面使用的中文文案都有对应英文，且占位符完全一致', () => {
    const required = [
      '本次生成消耗 {cost} 积分',
      '重新生成歌词同样消耗 {cost} 积分',
      '积分不足，当前余额 {balance} 积分，生成需要 {cost} 积分。',
      '歌词生成成功后保留积分扣费。',
      '生成失败、超时或取消将自动退还积分。',
      '积分规则',
    ]
    for (const key of required) {
      expect(english[key], `缺少英文文案：${key}`).toBeTruthy()
      expect([...english[key]!.matchAll(/\{\w+\}/g)].map(m => m[0]).sort())
        .toEqual([...key.matchAll(/\{\w+\}/g)].map(m => m[0]).sort())
    }
  })

  it('冲突的次数额度文案已从字典中移除', () => {
    expect(english['今日歌词编写不限次数']).toBeUndefined()
    expect(english['今日还可编写 {remaining} 次歌词']).toBeUndefined()
    expect(english['重新生成会使用一次歌词创作额度。']).toBeUndefined()
  })
})
