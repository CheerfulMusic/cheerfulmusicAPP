// Loaded before the creator and public listening pages so sharing starts in the user's click.
(() => {
  const desktopUrl = 'weixin://'

  function mode(destination) {
    const agent = navigator.userAgent || ''
    if (/MicroMessenger/i.test(agent)) return ''
    const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(agent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    if (mobile) return typeof navigator.share === 'function' ? 'system' : ''
    return destination === 'friend' && /Windows NT/i.test(agent) ? 'desktop' : ''
  }

  async function open(destination, data, isCurrent) {
    switch (mode(destination)) {
      case 'desktop':
        try {
          await navigator.clipboard.writeText(data.url)
        } catch {
          return { message: '请先复制下方歌曲链接，再打开电脑微信。', manual: true }
        }
        // Permission prompts can consume the click. The visible launch link supplies a fresh one.
        if (isCurrent() && navigator.userActivation?.isActive !== false) {
          try { location.assign(desktopUrl) } catch { /* The retry link and QR stay available. */ }
        }
        return { message: '歌曲链接已复制。在微信中选择好友，粘贴并发送。' }
      case 'system':
        try {
          if (!navigator.canShare || navigator.canShare(data)) {
            await navigator.share(data)
            return
          }
        } catch (error) { if (error?.name === 'AbortError') return }
        return { message: '系统分享暂不可用，可用微信扫描二维码。' }
    }
  }

  window.cheerfulWechat = { mode, open, desktopUrl }
})()
