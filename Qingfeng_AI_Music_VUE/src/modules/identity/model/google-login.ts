export function googleLoginFeedback(result: unknown, authenticated: boolean): { kind: 'success' | 'info' | 'error'; message: string } | undefined {
  switch (result) {
    case 'success':
      return authenticated
        ? { kind: 'success', message: 'Google 登录成功，可以用同一账号找回自己的音乐。' }
        : { kind: 'error', message: 'Google 登录状态尚未确认，请刷新后重试。' }
    case 'cancelled':
      return { kind: 'info', message: '已取消 Google 登录。' }
    case 'conflict':
      return { kind: 'error', message: '这个 Google 账号已关联另一份音乐收藏。请使用原账号登录查看当前作品。' }
    case 'failed':
      return { kind: 'error', message: 'Google 登录未完成，请重试。' }
    default:
      return undefined
  }
}
