<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { locale, t } from '../../shared/i18n'
const en = computed(() => locale.value === 'en')
const email = 'labelmanager@cheerfulmusic.co.uk'
const mail = (feedback = false) => `mailto:${email}?subject=${encodeURIComponent(feedback ? 'Cheerful Music — Feedback' : 'Cheerful Music — Support')}&body=${encodeURIComponent(en.value ? 'Account identifier:\nWork link or order ID (if relevant):\nWhat happened / suggestion:\nTime and timezone:\nBrowser and device:\nSteps to reproduce:\n' : '账号标识：\n作品链接或订单号（如相关）：\n问题描述／建议：\n发生时间与时区：\n浏览器与设备：\n复现步骤：\n')}`
const draftOpen = ref(false)
const draft = ref('')
const draftField = ref<HTMLTextAreaElement | null>(null)
const draftStatus = ref('')
async function openDraft() {
  if (!draftOpen.value) draft.value = new URL(mail(true)).searchParams.get('body') ?? ''
  draftOpen.value = true
  draftStatus.value = ''
  await nextTick()
  draftField.value?.focus()
}
const draftMail = computed(() => `mailto:${email}?subject=${encodeURIComponent('Cheerful Music — Feedback')}&body=${encodeURIComponent(draft.value)}`)
async function copyDraft() {
  try {
    await navigator.clipboard.writeText(`${en.value ? 'To' : '收件人'}: ${email}\n${en.value ? 'Subject' : '主题'}: Cheerful Music — Feedback\n\n${draft.value}`)
    draftStatus.value = en.value ? 'Copied. Paste into your email service and send it yourself.' : '已复制，请粘贴到你的邮箱中并自行发送。'
  } catch {
    draftField.value?.focus()
    draftField.value?.select()
    draftStatus.value = en.value ? 'Could not copy automatically. The body is selected; copy it manually and use the recipient shown above.' : '自动复制失败，已选中正文，请手动复制，并填写上方收件人。'
  }
}
function attemptedMail() {
  draftStatus.value = en.value ? 'Requested your email app. If nothing opens, copy the draft into your email service. Feedback has not been submitted.' : '已尝试打开邮件应用。如未打开，请复制草稿到你的邮箱发送。反馈尚未提交。'
}
const steps = computed(() => en.value ? [
  ['Sign in', 'Use an available sign-in method and keep using the same account to access your music.'],
  ['Create your song', 'Open Create, describe your story and choose a style. Review and edit the lyrics before generating music. Check the credit cost displayed before submitting.'],
  ['Find and listen', 'Open My Music to see drafts and generation progress. Open a completed work to listen, edit its details or download using the available controls.'],
  ['Share and publish', 'Use the sharing or publishing controls on the song page. Publishing makes the song available on Discover and your artist page. Review the community rules and confirm you have permission to use the material.']
] : [
  ['登录账号', '选择页面提供的登录方式。后续使用同一个账号登录，查看和管理自己的音乐。'],
  ['创作歌曲', '进入“开始创作”，填写故事并选择风格。先检查和修改歌词，再生成音乐；提交前查看页面显示的积分消耗。'],
  ['查看与试听', '进入“我的音乐”查看草稿和生成进度。歌曲完成后打开作品详情，即可试听，并使用页面提供的编辑、下载等功能。'],
  ['分享与发布', '在歌曲详情中选择分享或发布。发布后，歌曲可在广场和歌手主页展示。发布前阅读社区规则，并确认拥有所用素材的必要授权。']
])
const questions = computed(() => en.value ? [
  ['Where is my music?', 'Sign in with the same account used to create it, then refresh My Music. Different accounts may have different libraries.'],
  ['The verification code did not arrive', 'Check your address or phone number, spam folder and supported region. Wait for the resend countdown, then request a new code. Do not share codes with anyone.'],
  ['Generation is still running or being checked', 'Open the work to check its status. Avoid submitting repeatedly while the result is being checked. If it remains unresolved, send support the work link and time.'],
  ['How are credits charged?', 'See Recharge for current rates and read the cost shown before each operation. Account shows your balance and orders.'],
  ['I paid but credits have not arrived', 'Refresh your account and check the order status. Credits are added after payment is confirmed. Do not pay again solely because the balance has not refreshed; send support the order ID if unresolved.'],
  ['Can I cancel or request a refund?', 'Check the available order actions in Account. Cancellation and refund eligibility are governed by the service terms and the verified payment status; contacting support does not itself approve a refund.'],
  ['Why can I not publish a draft?', 'A playable song is needed before publishing. Complete generation, then open the song details and follow the publication steps.'],
  ['How do I report content or a privacy issue?', 'Email the contact below with the relevant link and a description. Avoid including unrelated personal information. See the privacy policy and community rules for details.']
] : [
  ['找不到之前的作品怎么办？', '请使用创作时的同一账号登录，再刷新“我的音乐”。不同账号可能对应不同的作品列表。'],
  ['收不到验证码怎么办？', '核对邮箱或手机号、垃圾邮件及支持的地区。等待重发倒计时结束后再获取验证码，不要向他人提供验证码。'],
  ['歌曲一直生成中或结果待核对怎么办？', '打开作品查看最新状态。结果核对期间避免反复提交；如果持续未解决，请将作品链接和发生时间提供给客服。'],
  ['积分如何扣除？', '充值页列出当前积分规则，每次操作前也请查看费用提示。账号页可查看积分余额和订单。'],
  ['已付款但积分未到账怎么办？', '刷新账号页并检查订单状态。付款确认后才会发放积分；不要仅因余额未刷新就重复付款。仍未解决时，请提供订单号联系支持。'],
  ['能否取消订单或退款？', '请在账号页查看订单可用操作。取消及退款条件以用户服务条款和核实后的支付状态为准，联系客服不代表退款申请已获批准。'],
  ['为什么草稿不能发布？', '发布需要已有可试听的歌曲。请先完成歌曲生成，再到作品详情按提示发布。'],
  ['如何反馈侵权内容或隐私问题？', '请通过下方邮箱提供相关链接及问题说明，避免附带无关个人信息。具体规则请查看隐私政策和音乐作品发布与社区规则。']
])
</script>

<template>
  <div class="help-page">
    <header><p class="eyebrow">CHEERFUL MUSIC</p><h1>{{ t('帮助与支持') }}</h1><p>{{ en ? 'Find your next step, resolve an issue, or get in touch.' : '了解如何创作、处理常见问题，或联系我们。' }}</p></header>
    <nav class="help-nav" :aria-label="t('帮助与支持')"><a href="#guide">{{ t('使用指南') }}</a><a href="#faq">{{ t('常见问题 (FAQ)') }}</a><a href="#contact">{{ t('联系客服') }}</a><a href="#feedback">{{ t('反馈建议') }}</a></nav>
    <section id="guide"><h2>{{ t('使用指南') }}</h2><ol><li v-for="[title, text] in steps" :key="title"><h3>{{ title }}</h3><p>{{ text }}</p></li></ol><div class="help-links"><RouterLink to="/create">{{ t('开始创作') }} →</RouterLink><RouterLink to="/works">{{ t('我的音乐') }} →</RouterLink></div></section>
    <section id="faq"><h2>{{ t('常见问题 (FAQ)') }}</h2><details v-for="[question, answer] in questions" :key="question"><summary>{{ question }}</summary><p>{{ answer }}</p></details><div class="help-links"><RouterLink to="/recharge">{{ en ? 'Credit rates' : '查看积分规则' }}</RouterLink><RouterLink to="/account">{{ en ? 'Account and orders' : '查看账号与订单' }}</RouterLink></div></section>
    <section id="contact"><h2>{{ t('联系客服') }}</h2><p>{{ en ? 'For account, creation or payment issues, email us with your account identifier, relevant work link or order ID, the time, and a description. Screenshots can help; redact sensitive details.' : '账号、创作或支付问题，请邮件提供账号标识、相关作品链接或订单号、发生时间和问题描述。可附上截图，并遮盖敏感信息。' }}</p><a class="help-email" :href="mail()">{{ email }}</a><p>{{ en ? 'This opens your email app; no support request is submitted on this page. If no mail app opens, copy the address into your email service.' : '点击邮箱将打开邮件应用，本页面不会直接提交工单。如未打开邮件应用，请复制邮箱地址自行发送。' }}</p><p class="privacy-note">{{ en ? 'Never send passwords, verification codes, recovery codes or full payment card details.' : '请勿发送密码、验证码、作品找回码或完整银行卡信息。' }}</p></section>
    <section id="feedback"><h2>{{ t('反馈建议') }}</h2><p>{{ en ? 'Tell us which feature you were using, what you expected, and what happened. For a bug, include your browser, device and steps to reproduce. For an idea, describe the problem you want to solve.' : '请说明使用的功能、期望的效果和实际情况。反馈故障时可补充浏览器、设备及复现步骤；提出建议时，请描述希望解决的问题。' }}</p><button class="button" type="button" :aria-expanded="draftOpen" aria-controls="feedback-draft" @click="openDraft">{{ en ? 'Write a feedback email' : '撰写反馈邮件' }}</button><p>{{ en ? 'Edit a draft here, then copy it into your email service or open your email app. Feedback is only sent when you send the email.' : '先在本页填写草稿，再复制到你的邮箱或打开邮件应用发送。填写草稿不代表反馈已提交。' }}</p>
      <div v-if="draftOpen" id="feedback-draft" class="feedback-draft">
        <p>{{ en ? 'To' : '收件人' }}：<span>{{ email }}</span><br>{{ en ? 'Subject' : '主题' }}：Cheerful Music — Feedback</p>
        <label for="feedback-body">{{ en ? 'Email body' : '邮件正文' }}</label>
        <textarea id="feedback-body" ref="draftField" v-model="draft" rows="9" aria-describedby="feedback-privacy" />
        <p id="feedback-privacy">{{ en ? 'Do not include passwords, verification codes or full card details.' : '请勿填写密码、验证码或完整银行卡信息。' }}</p>
        <div class="help-links"><button type="button" class="button" @click="copyDraft">{{ en ? 'Copy email content' : '复制邮件内容' }}</button><a :href="draftMail" @click="attemptedMail">{{ en ? 'Open email app' : '打开邮件应用' }}</a></div>
        <p role="status" aria-live="polite">{{ draftStatus }}</p>
      </div></section>
    <aside class="help-links"><a href="/legal/terms.html">{{ t('用户服务条款（含积分与退款规则）') }}</a><a href="/legal/privacy.html">{{ t('隐私政策') }}</a><a href="/legal/community.html">{{ t('音乐作品发布与社区规则') }}</a></aside>
  </div>
</template>

<style scoped>
.help-page{max-width:1000px;margin:0 auto;padding:48px 20px 64px;color:var(--text,#302826)}
header{margin-bottom:28px}h1{font-size:clamp(30px,5vw,44px);margin:8px 0 16px}.eyebrow{font-size:12px;letter-spacing:.16em;color:#b0503b}
p{line-height:1.85;overflow-wrap:anywhere}.help-nav,.help-links{display:flex;flex-wrap:wrap;gap:14px 24px}.help-nav{margin-bottom:28px;padding:18px 0;border-block:1px solid #efddd6}
a{color:#bd402e;text-underline-offset:4px}section{scroll-margin-top:110px;padding:28px;margin:20px 0;border:1px solid #efddd6;border-radius:22px;background:var(--surface-raised,#fff)}h2{margin:0 0 22px;font-size:25px}h3{font-size:18px;margin-bottom:6px}li{padding-left:8px;margin-bottom:20px}details{padding:17px 0;border-bottom:1px solid #efddd6}summary{cursor:pointer;font-weight:600;line-height:1.6}details p{margin-bottom:2px}.help-links{margin-top:24px}.help-email{font-size:clamp(16px,3vw,21px);overflow-wrap:anywhere}.privacy-note{background:#fff3ed;padding:12px 16px;border-radius:12px}button:focus-visible,textarea:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid #f39b7d;outline-offset:5px}@media(max-width:600px){.help-page{padding:28px 16px 40px}section{padding:22px 18px}ol{padding-left:24px}}
.feedback-draft{margin-top:20px;padding-top:16px;border-top:1px solid #efddd6}.feedback-draft label{display:block;font-weight:600;margin-bottom:8px}.feedback-draft textarea{box-sizing:border-box;width:100%;padding:14px;border:1px solid #dcbcb1;border-radius:12px;font:inherit;line-height:1.7;resize:vertical;background:#fff;color:#302826}.feedback-draft .help-links{align-items:center}button{cursor:pointer;font:inherit}
</style>
