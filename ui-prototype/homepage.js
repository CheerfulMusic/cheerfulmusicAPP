// 试听播放器：本地器乐样曲，不调用生成服务。
let featuredTrack='sea', activePreview=null;
const previewAudio = new Audio();previewAudio.preload='metadata';
function ensurePreviewPlayer(){
 if(document.querySelector('#preview-player'))return;
 const player=document.createElement('aside');player.id='preview-player';player.className='q-player';player.hidden=true;player.setAttribute('aria-label','试听播放器');
 player.innerHTML=`<img id="player-art" alt="当前试听封面" width="48" height="48"><div class="q-player-info"><strong id="player-title"></strong><span>器乐界面样曲</span></div><button id="player-toggle" class="q-player-toggle" aria-label="播放试听">▶</button><span id="player-time">00:00</span><input id="player-progress" type="range" min="0" max="100" step="0.1" value="0" aria-label="试听进度"><span id="player-duration">00:18</span><button id="player-close" class="q-player-close" aria-label="关闭试听">×</button>`;
 document.body.append(player);
 document.querySelector('#player-toggle').addEventListener('click',()=>activePreview&&playPreview(activePreview.id));
 document.querySelector('#player-close').addEventListener('click',()=>{previewAudio.pause();player.hidden=true;document.body.classList.remove('has-preview');});
 document.querySelector('#player-progress').addEventListener('input',event=>{if(Number.isFinite(previewAudio.duration))previewAudio.currentTime=Number(event.target.value)/100*previewAudio.duration;});
}
function updatePreviewButtons(){document.querySelectorAll('[data-audio]').forEach(button=>{const playing=activePreview?.id===button.dataset.audio&&!previewAudio.paused;const track=previewTracks.find(t=>t.id===button.dataset.audio);button.classList.toggle('playing',playing);button.setAttribute('aria-label',`${playing?'暂停':'试听'}${track.title}`);const glyph=button.querySelector('.q-play-glyph');if(glyph)glyph.textContent=playing?'Ⅱ':'▶';const label=button.querySelector('.p-play-label');if(label)label.textContent=playing?'暂停':'试听';});const toggle=document.querySelector('#player-toggle');if(toggle){toggle.textContent=previewAudio.paused?'▶':'Ⅱ';toggle.setAttribute('aria-label',previewAudio.paused?'播放试听':'暂停试听');}}
async function playPreview(id){
 const track=previewTracks.find(t=>t.id===id);if(!track)return;ensurePreviewPlayer();
 const sameTrack=activePreview?.id===id;
 if(!sameTrack){activePreview=track;previewAudio.src=track.audio;document.querySelector('#player-art').src=track.cover;document.querySelector('#player-title').textContent=track.title;document.querySelector('#player-progress').value=0;document.querySelector('#player-time').textContent='00:00';}
 document.querySelector('#preview-player').hidden=false;document.body.classList.add('has-preview');
 try{if(sameTrack&&!previewAudio.paused)previewAudio.pause();else await previewAudio.play();}catch{toast('试听暂时无法播放，请重新点击播放，或通过本地服务打开页面。');}updatePreviewButtons();
}
const timeLabel=seconds=>`00:${String(Math.floor(seconds||0)).padStart(2,'0')}`;
previewAudio.addEventListener('timeupdate',()=>{if(!document.querySelector('#player-time'))return;document.querySelector('#player-time').textContent=timeLabel(previewAudio.currentTime);document.querySelector('#player-duration').textContent=timeLabel(previewAudio.duration);document.querySelector('#player-progress').value=Number.isFinite(previewAudio.duration)?previewAudio.currentTime/previewAudio.duration*100:0;});
['play','pause','ended'].forEach(event=>previewAudio.addEventListener(event,updatePreviewButtons));
document.addEventListener('click',event=>{
 const button=event.target.closest('button');if(!button)return;
 if(button.dataset.audio){playPreview(button.dataset.audio);return;}
 if(button.dataset.feature){const track=previewTracks.find(t=>t.id===button.dataset.feature);featuredTrack=track.id;document.querySelector('#featured-cover').innerHTML=coverMarkup(track,'q-feature-cover');document.querySelector('#featured-title').textContent=track.title;document.querySelector('#featured-description').textContent=track.description;document.querySelector('#feature-play').dataset.audio=track.id;document.querySelectorAll('[data-feature]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.feature===track.id));updatePreviewButtons();return;}
 if(button.dataset.filter){document.querySelectorAll('[data-filter]').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',b===button);});document.querySelectorAll('[data-track-card]').forEach(card=>card.hidden=button.dataset.filter!=='all'&&card.dataset.trackCard!==button.dataset.filter);}
});

previewAudio.addEventListener('timeupdate',()=>{const progress=document.querySelector('.p-static-progress>span');if(progress&&document.querySelector('#feature-play')?.dataset.audio===activePreview?.id&&Number.isFinite(previewAudio.duration))progress.style.width=(previewAudio.currentTime/previewAudio.duration*100)+'%';});
