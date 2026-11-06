<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import { loginDialogOpen } from '../../../shared/identity/login-dialog'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
const route=useRoute(),router=useRouter(),dialog=ref<HTMLDialogElement>(),start=ref<HTMLButtonElement>()
const authenticated=computed(()=>accountState.value.authenticated||logtoState.value.authenticated)
const amount=ref(100),claimed=new Set<string>()
let checking=false,alive=true
async function check(){
 if(checking||!authenticated.value||loginDialogOpen.value||route.name==='gift')return
 checking=true
 try{
  await ensureCreator();const owner=currentCreatorId();if(claimed.has(owner))return
  const result=await requestJson<{show:boolean;amount:number}>('app/welcome','POST',{})
  claimed.add(owner)
  if(!alive||owner!==currentCreatorId()||!authenticated.value||!result.show||result.amount!==100)return
  amount.value=result.amount;await nextTick();dialog.value?.showModal();start.value?.focus()
 }catch{/* Keep login usable; a later confirmed identity refresh can retry an unavailable welcome request. */}
 finally{checking=false}
}
watch([authenticated,loginDialogOpen,()=>route.fullPath],()=>{if(!authenticated.value)dialog.value?.close();void check()},{immediate:true,flush:'post'})
function close(){dialog.value?.close()}
function begin(){close();void router.push('/create')}
onBeforeUnmount(()=>{alive=false;dialog.value?.close()})
</script>
<template>
 <dialog ref="dialog" class="welcome-modal" aria-labelledby="welcome-heading" aria-describedby="welcome-description" @click="($event.target===dialog)&&close()">
  <button class="welcome-close" type="button" :aria-label="t('关闭')" @click="close"><span aria-hidden="true">×</span></button>
  <div class="welcome-art"><img src="/illustrations/welcome/music-gift.png" alt="" width="640" height="640"><p>{{ t('用音乐，点亮生活') }}<span aria-hidden="true">♥</span></p></div>
  <section class="welcome-copy">
   <p class="welcome-eyebrow">CHEERFUL MUSIC · WELCOME</p>
   <h2 id="welcome-heading">{{ t('欢迎加入') }}<strong>{{ t('青风音乐') }}</strong>！</h2>
   <p id="welcome-description" class="welcome-subtitle">{{ t('恭喜您获得新人专属福利') }}</p>
   <div class="welcome-reward"><span class="welcome-coin" aria-hidden="true">★</span><b>{{ amount }}</b><span>{{ t('积分') }}</span></div>
   <p class="welcome-issued">{{ t('已自动发放到您的账户中') }}</p>
   <button ref="start" class="welcome-start" type="button" @click="begin">{{ t('开启音乐之旅') }}<span aria-hidden="true"> →</span></button>
  </section>
  <svg class="welcome-waves" viewBox="0 0 1120 125" preserveAspectRatio="none" aria-hidden="true"><path d="M0 40Q220 160 440 80T800 95T1120 0V125H0Z" fill="#ffc7b6" opacity=".6"/><path d="M0 75Q200 140 450 100T800 120T1120 18V125H0Z" fill="#ff9baf" opacity=".7"/><path d="M0 105Q240 150 510 75T880 125T1120 85V125H0Z" fill="#ffbb73" opacity=".55"/></svg>
 </dialog>
</template>
<style scoped>
.welcome-modal,.welcome-modal *{box-sizing:border-box}
.welcome-modal{position:fixed;width:min(1120px,calc(100vw - 40px));max-width:none;max-height:calc(100svh - 40px);margin:auto;padding:54px 54px 66px;overflow:auto;border:1px solid #ffe7e5;border-radius:32px;background:radial-gradient(ellipse at 15% 15%,#ffd0e2,transparent 52%),radial-gradient(ellipse at 96% 0%,#ffe0e5,transparent 42%),linear-gradient(115deg,#fff0f4,#fff9ef 65%,#ffe8ed);color:#491620;box-shadow:0 30px 100px #542a3838;grid-template-columns:44% 56%;align-items:center;isolation:isolate}
.welcome-modal[open]{display:grid}.welcome-modal::backdrop{background:#3d26374f;backdrop-filter:blur(9px)}
.welcome-close{position:absolute;right:23px;top:13px;z-index:3;width:44px;height:44px;padding:0;border:0;border-radius:50%;background:transparent;color:#491620;font-size:48px;line-height:1;cursor:pointer}
.welcome-art{position:relative;z-index:1;margin-left:-35px;text-align:center}.welcome-art img{display:block;width:100%;height:auto;filter:drop-shadow(0 18px 22px #ff967247)}.welcome-art p{margin:0 0 0 15px;color:#982a3c;font-family:cursive;font-size:clamp(20px,2.4vw,29px);transform:rotate(-7deg);letter-spacing:.08em}.welcome-art p span{display:inline-block;margin-left:15px;color:#ff867f}
.welcome-copy{z-index:1;text-align:center}.welcome-eyebrow{margin:0 0 14px;font-size:10px;letter-spacing:.22em;color:#b6777a}.welcome-copy h2{margin:0;white-space:normal;font-size:clamp(26px,3.2vw,46px);font-weight:800;letter-spacing:-.05em;line-height:1.4}.welcome-copy h2 strong{margin-left:.15em;background:linear-gradient(#aa2536,#ff6763);background-clip:text;color:transparent}.welcome-subtitle{margin:12px 0 25px;font-size:clamp(17px,2vw,25px);color:#6f5d61}.welcome-reward{display:flex;justify-content:center;align-items:center;gap:15px;padding:14px 15px;border:1px solid #ffd2c4;border-radius:24px;background:linear-gradient(110deg,#fffcf6,#ffe8dc);box-shadow:0 4px 9px #da5b4820,inset 0 1px 0 #fff}.welcome-reward b{font-size:clamp(76px,8.6vw,126px);font-style:italic;font-weight:850;line-height:1.12;letter-spacing:-.08em;background:linear-gradient(#f33339,#ff7044);background-clip:text;color:transparent;padding-right:5px}.welcome-reward>span:last-child{align-self:flex-end;margin-bottom:18px;color:#ff604b;font-size:clamp(22px,2.8vw,36px);font-weight:750}.welcome-coin{display:grid;place-items:center;flex-shrink:0;width:70px;height:70px;border:5px solid #ffc642;border-radius:50%;background:linear-gradient(145deg,#ffe981,#ffba25);color:#ffe895;font-size:42px;text-shadow:0 2px 1px #db8908;box-shadow:inset 0 0 0 3px #ffdd66,0 5px 5px #ee90292b}.welcome-issued{margin:17px 0 38px;color:#827275;font-size:clamp(16px,1.8vw,23px)}.welcome-start{min-height:66px;padding:15px 42px;border:0;border-radius:99px;background:linear-gradient(105deg,#ff4e7c,#ffa25b);color:#fff;font-size:clamp(18px,2vw,25px);font-weight:700;cursor:pointer;box-shadow:0 8px 22px #ff87752b}.welcome-start:hover{filter:brightness(1.04);transform:translateY(-1px)}.welcome-start span{margin-left:12px}.welcome-waves{position:absolute;bottom:0;left:0;width:100%;height:90px;z-index:0;pointer-events:none}.welcome-close:focus-visible,.welcome-start:focus-visible{outline:3px solid #a92b48;outline-offset:4px}
@media(max-width:700px){.welcome-modal{width:calc(100vw - 28px);padding:30px 23px 43px;border-radius:25px;grid-template-columns:1fr}.welcome-close{top:8px;right:10px;font-size:37px}.welcome-art{margin:0 auto;width:210px}.welcome-art p{display:none}.welcome-copy h2{font-size:29px}.welcome-eyebrow{font-size:8px;margin:0 0 9px}.welcome-subtitle{margin:8px 0 16px;font-size:17px}.welcome-reward{padding:9px;gap:12px;border-radius:19px}.welcome-reward b{font-size:88px}.welcome-coin{width:52px;height:52px;font-size:31px;border-width:4px}.welcome-reward>span:last-child{font-size:26px;margin-bottom:13px}.welcome-issued{margin:14px 0 22px;font-size:17px}.welcome-start{min-height:55px;font-size:20px;padding:12px 30px}.welcome-waves{height:62px}}
@media(prefers-reduced-motion:reduce){.welcome-start:hover{transform:none}}
</style>
