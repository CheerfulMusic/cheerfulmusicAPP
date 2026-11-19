// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import BrowserDownloadHelp from '../src/shared/BrowserDownloadHelp.vue'
import { showBrowserDownloadHelp, closeBrowserDownloadHelp, browserDownloadOpen } from '../src/shared/browser-download'
afterEach(() => { closeBrowserDownloadHelp(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('offers the page link, copies it and closes the download guidance', async () => {
  vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function(this: HTMLDialogElement) { this.open = true })
  vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function(this: HTMLDialogElement) { this.open = false })
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const wrapper = mount(BrowserDownloadHelp)
  showBrowserDownloadHelp()
  await flushPromises()
  expect(wrapper.get('dialog').attributes()).toHaveProperty('open')
  expect(wrapper.get('input').element.value).toBe(window.location.href)
  expect(wrapper.text()).toContain('本次操作未发起解锁或扣费')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(writeText).toHaveBeenCalledWith(window.location.href)
  expect(wrapper.text()).toContain('链接已复制')
  await wrapper.findAll('button')[1]!.trigger('click')
  expect(browserDownloadOpen.value).toBe(false)
  wrapper.unmount()
})
