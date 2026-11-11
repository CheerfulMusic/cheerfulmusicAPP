// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import InspirationPanel from '../src/modules/studio/components/InspirationPanel.vue'

it('cycles through ten distinct groups without adding a story until a tag is chosen', async () => {
  const panel = mount(InspirationPanel, { props: { disabled: false } })
  try {
    const labels = () => panel.findAll('.inspiration-strip-option').map(button => button.text())
    const first = labels()
    const groups = new Set<string>()
    const allLabels = new Set<string>()
    for (let index = 0; index < 10; index++) {
      const group = labels()
      expect(group).toHaveLength(4)
      groups.add(group.join('|'))
      group.forEach(label => allLabels.add(label))
      await panel.get('.inspiration-strip-refresh').trigger('click')
    }
    expect(groups.size).toBe(10)
    expect(allLabels.size).toBe(40)
    expect(labels()).toEqual(first)
    expect(panel.emitted('choose')).toBeUndefined()

    const option = panel.findAll('.inspiration-strip-option')[2]!
    await option.trigger('click')
    const preview = panel.get('.inspiration-strip-preview').text()
    expect(option.attributes('aria-pressed')).toBe('true')
    expect(preview).toContain('总说「没关系」')
    expect(panel.emitted('choose')).toEqual([[preview]])
    await panel.setProps({ disabled: true })
    expect(panel.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  } finally {
    panel.unmount()
  }
})
