// Loaded before the creator and public listening pages so sharing starts in the user's click.
(() => {
  const desktopUrl = 'weixin://'

  function mode(destination) {
    const agent = navigator.userAgent || ''
    if (/MicroMessenger/i.test(agent)) return ''
    const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(agent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    if (!mobile && /Windows NT/i.test(agent) && destination === 'friend') return 'desktop'
    return typeof navigator.share === 'function' ? 'system' : ''
  }

  function canShare(data) {
    try { return typeof navigator.share === 'function' && (!navigator.canShare || navigator.canShare(data)) }
    catch { return false }
  }

  function draft(data) {
    return [data.title, data.url].filter(Boolean).join('\n')
  }

  async function copyDraft(data) {
    try {
      await navigator.clipboard.writeText(draft(data))
      return { clipboard: 'copied', message: '歌曲和链接已复制。打开好友聊天框，按 Ctrl+V 粘贴后发送。' }
    } catch {
      return { clipboard: 'manual', message: '请按 Ctrl+C 复制待发送内容，再到微信好友聊天框按 Ctrl+V 粘贴。' }
    }
  }

  async function open(destination, data) {
    const channel = mode(destination)
    if (!channel) return
    if (canShare(data)) {
      try {
        await navigator.share(data)
        return
      } catch (error) {
        if (error?.name === 'AbortError') return
      }
    } else if (channel === 'desktop') {
      return copyDraft(data)
    }
    if (channel === 'desktop') {
      return { message: '复制下方内容后，可在微信好友聊天框按 Ctrl+V 粘贴。' }
    }
    return { message: '系统分享暂不可用，可用微信扫描二维码。' }
  }

  window.cheerfulWechat = { mode, canShare, draft, copyDraft, open, desktopUrl }
})()
