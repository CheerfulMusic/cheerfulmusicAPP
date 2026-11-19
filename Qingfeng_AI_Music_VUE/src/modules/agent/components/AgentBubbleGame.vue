<script setup lang="ts">
import { ref } from 'vue'
import { locale } from '../../../shared/i18n'

const tr=(zh:string,en:string)=>locale.value==='en'?en:zh
const notes=['♪','♫','♩','♬']
const phase=ref<'ready'|'playing'|'won'>('ready')
const bubbles=ref<{note:number;cleared:boolean}[]>([])
const selected=ref<number|null>(null)
const pairs=ref(0)
const mismatch=ref(false)

function start(){
  const deck=[0,0,1,1,2,2,3,3]
  for(let i=deck.length-1;i>0;i--){
    const j=Math.floor(Math.random()*(i+1))
    const value=deck[i]!
    deck[i]=deck[j]!
    deck[j]=value
  }
  bubbles.value=deck.map(note=>({note,cleared:false}))
  pairs.value=0
  selected.value=null
  mismatch.value=false
  phase.value='playing'
}
function pick(index:number){
  const bubble=bubbles.value[index]
  if(!bubble || bubble.cleared)return
  const first=selected.value
  mismatch.value=false
  if(first===null){selected.value=index;return}
  if(first===index){selected.value=null;return}
  if(bubbles.value[first]!.note!==bubble.note){
    mismatch.value=true
    selected.value=index
    return
  }
  bubbles.value[first]!.cleared=true
  bubble.cleared=true
  selected.value=null
  if(++pairs.value===notes.length)phase.value='won'
}
</script>
<template>
  <section class="bubble-game" :class="{active:phase==='playing'}" :aria-label="tr('梦幻泡泡消除','Melody bubbles')">
    <div v-if="phase!=='playing'" class="game-intro">
      <h3>{{ tr('梦幻泡泡消除','Melody bubbles') }}</h3>
      <p role="status">{{ phase==='won'?tr('四对音符全部消除！','All four pairs cleared!'):tr('点两个相同音符，消除泡泡','Tap two matching notes to pop them') }}</p>
      <button type="button" @click="start">{{ phase==='won'?tr('再玩一次','Play again'):tr('开始游戏','Play game') }} ▸</button>
    </div>
    <template v-else>
      <header><strong>{{ tr('梦幻泡泡消除','Melody bubbles') }}</strong><small>{{ pairs }} / 4</small><button type="button" @click="phase='ready'">{{ tr('退出','Exit') }}</button></header>
      <div class="bubble-board"><button v-for="(bubble,index) in bubbles" :key="index" type="button" :data-note="bubble.note" :disabled="bubble.cleared" :aria-label="tr('音符','Note')+' '+notes[bubble.note]" :aria-pressed="selected===index" @click="pick(index)">{{ notes[bubble.note] }}</button></div>
      <small role="status">{{ mismatch?tr('试试与选中泡泡相同的音符','Find a match for the selected note'):tr('点选一对相同音符','Select a matching pair') }}</small>
    </template>
  </section>
</template>
<style scoped>
.bubble-game{position:relative;isolation:isolate;overflow:hidden;flex:none;margin:7px 0;padding:12px;border-radius:15px;color:white;background:radial-gradient(ellipse at 90% 125%,#ca79ed,transparent 68%),linear-gradient(130deg,#401159,#411885 60%,#4e1f83);box-shadow:0 4px 14px #63359d2b}
.bubble-game:not(.active)::before{content:'';position:absolute;z-index:-1;inset:0 46% 0 0;background:url('../assets/bubble-game.png') 5% 53% / 218% 118% no-repeat;mask-image:linear-gradient(90deg,black 75%,transparent)}
.game-intro{margin-left:40%;text-align:center}.game-intro h3{margin:0;font-size:clamp(14px,1.12vw,21px);color:white}.game-intro p,.bubble-game small{margin:5px 0;font-size:11px;line-height:1.35;color:#f1e1ff}
.bubble-game button{cursor:pointer;color:white}.game-intro button{padding:5px 15px;border-radius:20px;border:1px solid #e9baff;background:linear-gradient(#d88ffe,#9d47ed);font-weight:650}
.bubble-game header{display:flex;align-items:center;gap:6px;color:white;margin:0}.bubble-game header small{margin-left:auto}.bubble-game header button{border:0;background:transparent}.bubble-board{display:grid;grid-template-columns:repeat(4,1fr);gap:5px;justify-items:center;margin:7px 0}
.bubble-board button{width:32px;height:32px;border-radius:50%;border:1px solid #ffffffba;background:radial-gradient(circle at 37% 30%,#ffd9ff,#ca6bef 58%,#8532b8);font-size:18px;line-height:1}.bubble-board [data-note="1"]{background:radial-gradient(circle at 37% 30%,#c6ffff,#55bdee 58%,#3876cb)}.bubble-board [data-note="2"]{background:radial-gradient(circle at 37% 30%,#fff4b3,#f5ac42 58%,#b96619)}.bubble-board [data-note="3"]{background:radial-gradient(circle at 37% 30%,#d2fff2,#3bc59f 58%,#26796e)}.bubble-board [aria-pressed=true]{outline:2px solid white;outline-offset:2px}.bubble-board button:disabled{visibility:hidden}
</style>
