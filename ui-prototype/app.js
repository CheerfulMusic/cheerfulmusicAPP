// 纯前端交互原型：示例歌词、等待过程均为演示，不调用模型，不产生费用。
const app = document.querySelector('#app');
const state = { step: 0, occasion: '恋爱纪念', recipient: '', sender: '', story: '', message: '', style: '温暖民谣', mood: '温柔治愈', title: '下次，一起去看海', lyrics: '', generated: false };
const demoLyrics = '还记得那年夏天的晚风\n你把愿望写在我的手心\n我们穿过人海和黄昏\n把平凡的日子过成星辰\n\n下次，一起去看海\n让晚风把心事慢慢打开\n那些来不及说出口的爱\n都在这一首歌里，等你听来';
let generationTimer, toastTimer;
const safe = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const arrow = '<svg aria-hidden="true"><use href="#arrow"/></svg>';
function toast(message) { const el = document.querySelector('#toast'); el.textContent = message; el.classList.add('visible'); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('visible'), 3400); }
function navigate(hash) { if (location.hash === hash) render(); else location.hash = hash; }
function strip(){ return '<div class="review-strip">UI 交互原型 <span>生成与音频为演示占位</span><button data-action="home">首页</button><button data-action="create">创作流程</button><button data-action="gift-demo">礼物页</button></div>'; }
function render() {
 clearTimeout(generationTimer);
 const route = location.hash.slice(1) || 'home';
 document.body.classList.toggle('is-home', !['create','gift'].includes(route));
 if (route === 'create') renderCreate(); else if (route === 'gift') renderGift(); else { app.innerHTML=homeMarkup;updatePreviewButtons(); }
 window.scrollTo({top:0,behavior:'instant'});
}
function choices(key, options){return `<div class="chip-options" role="group" aria-label="${key === 'occasion' ? '送歌场合' : key === 'style' ? '音乐风格' : '歌曲情绪'}">${options.map(item=>`<button type="button" class="chip ${state[key]===item?'selected':''}" aria-pressed="${state[key]===item}" data-choice="${key}" data-value="${item}">${item}</button>`).join('')}</div>`;}
function renderCreate(){
 const labels=['送给谁','讲述故事','选择旋律','确认歌词'];
 const content = [
 `<div class="panel-kicker">A PERSON WORTH A SONG</div><h2>这首歌，你想送给谁？</h2><p class="panel-description">从一个名字开始，让心意有一个去处。</p><label class="field"><span>TA 的称呼 <em>必填</em></span><input id="recipient" maxlength="20" autocomplete="off" placeholder="例如：小雨、妈妈、亲爱的自己" value="${safe(state.recipient)}"></label><div class="field"><span class="field-label">在什么特别的时刻？</span>${choices('occasion',['生日祝福','恋爱纪念','朋友毕业','送给家人','送给自己','只是想你'])}</div><label class="field"><span>你的称呼 <em>选填 · 用于礼物署名</em></span><input id="sender" maxlength="20" placeholder="让 TA 知道，这份心意来自你" value="${safe(state.sender)}"></label>`,
 `<div class="panel-kicker">SMALL DETAILS, BIG FEELINGS</div><h2>讲讲属于你们的故事。</h2><p class="panel-description">不用写得很漂亮，真实的小细节就很动人。 <button class="sample-link" data-action="sample">试试填入示例</button></p><label class="field"><span>想写进歌里的回忆 <em>必填</em></span><textarea id="story" maxlength="1200" placeholder="你们在哪里认识？一起经历了什么？有没有一个特别的约定？">${safe(state.story)}</textarea></label><div class="story-prompts"><span>一个难忘的地点</span><span>只有你们懂的小事</span><span>一句特别的口头禅</span></div><label class="field"><span>最想对 TA 说的话 <em>选填</em></span><textarea id="message" maxlength="200" placeholder="例如：希望以后每个生日，都能一起度过。" style="min-height:85px">${safe(state.message)}</textarea></label>`,
 `<div class="panel-kicker">FIND THE SOUND OF YOUR STORY</div><h2>心里的这首歌，是什么声音？</h2><p class="panel-description">选一种喜欢的感觉，让故事顺着旋律生长。</p><div class="field"><span class="field-label">音乐风格</span>${choices('style',['温暖民谣','抒情流行','轻柔爵士'])}</div><div class="field"><span class="field-label">歌曲情绪</span>${choices('mood',['温柔治愈','浪漫深情','轻快明亮','怀念感动'])}</div><div class="aside-card" style="margin-top:30px"><div class="eyebrow">YOUR SONG IN A FEW WORDS</div><p>一首送给 <strong>${safe(state.recipient || '重要的人')}</strong> 的歌。<br>关于${safe(state.occasion)}，也关于那些值得被记住的日子。</p></div><p class="prototype-note">这里展示交互与视觉效果，实际可选风格将在音乐服务确认后调整。</p>`,
 `<div class="panel-kicker">MAKE IT SOUND LIKE YOU</div><h2>看看这些话，像不像你的心意？</h2><p class="panel-description">你可以修改歌名和歌词，确认后再生成歌曲。</p><label class="field"><span>歌曲名称</span><input id="title" maxlength="40" value="${safe(state.title)}"></label><label class="field"><span>你的专属歌词 <em>可以直接编辑</em></span><textarea id="lyrics" class="lyrics-editor" maxlength="3000">${safe(state.lyrics || demoLyrics)}</textarea></label><p class="prototype-note">当前为示例歌词，用于确认页面布局与编辑流程。真实生成将在后端接入后提供。</p>`
 ][state.step];
 app.innerHTML = strip()+`<section class="studio"><div class="breadcrumbs"><a href="#home">青风寄语</a><span> / </span><span>制作你的音乐礼物</span></div><div class="studio-heading"><div class="eyebrow">A LITTLE STORY. A SPECIAL SONG.</div><h1>让一段故事，有自己的旋律。</h1><p>慢慢写，我们一起把心意变成歌。</p></div><div class="stepper">${labels.map((label,i)=>`${i?'<i></i>':''}<div class="step-item ${i<=state.step?'active':''}" ${i===state.step?'aria-current="step"':''}><b>${i<state.step?'✓':i+1}</b>${label}</div>`).join('')}</div><div class="studio-layout"><section class="form-panel">${content}<div class="form-actions"><button class="text-button" data-action="back">← ${state.step?'上一步':'返回首页'}</button><button class="btn primary" data-action="next">${['下一步，讲讲故事','下一步，选择旋律','预览歌词（演示）','生成歌曲（演示）'][state.step]} ${arrow}</button></div></section><aside class="studio-aside"><div class="aside-card"><div class="eyebrow">A NOTE FROM QINGFENG</div><h3>最动听的歌词，<br>藏在平凡的日子里。</h3><p>也许是一起走过的那条路，<br>也许是每次都为你留着的那盏灯。<br>这些小事，就已经足够特别。</p><div class="aside-art"><img src="assets/family-editorial.png" alt="亲情音乐礼物场景，AI 图片"></div></div><p class="aside-tip"><strong>✧ 给你一个小提示</strong>具体的名字、地点和回忆，会让这首歌更像是为 TA 而唱。</p></aside></div></section>`;
}
function collect(){ ['recipient','sender','story','message','title','lyrics'].forEach(key=>{const el=document.getElementById(key);if(el)state[key]=el.value;}); }
function renderGift(){
 const recipient = state.recipient || '小雨', sender=state.sender||'一个惦记你的人';
 app.innerHTML=strip()+`<section class="gift-page"><div class="gift-topline"><a href="#home">← 返回青风寄语</a><span>一份可以被听见的心意 · 示例预览</span></div><div class="gift-heading"><div class="eyebrow">A SONG, ONLY FOR YOU</div><h1>亲爱的${safe(recipient)}，这首歌送给你。</h1><p>有些话，想用一首歌慢慢说给你听。</p></div><div class="gift-layout"><div class="gift-visual"><div class="gift-album"><img src="assets/couple-editorial.png" alt="音乐礼物封面，AI 场景图"><div class="album-caption"><span>青风寄语 / 给${safe(recipient)}</span><strong>风会替我<br>告诉你。</strong></div><span class="album-volume">YOUR STORY, YOUR MELODY.</span></div><p class="gift-quote">“${safe(state.message||'希望以后的每一个夏天，都有你在身边。')}”</p></div><div class="gift-content"><div class="panel-kicker">QINGFENG MUSIC / SPECIAL EDITION</div><h2>${safe(state.title)}</h2><span class="pill">${safe(state.style)}</span><div class="audio-ui"><div class="audio-row"><button class="audio-play" data-action="play" aria-label="播放歌曲（音频待接入）">▶</button><div class="audio-track"><div class="track-line"></div><div class="track-time"><span>00:00</span><span>--:--</span></div></div></div><p class="audio-note">音频展示占位 · 当前原型不含生成音频</p></div><div class="gift-lyrics"><span>THE WORDS FROM THE HEART / 歌词</span><pre>${safe(state.lyrics||demoLyrics)}</pre></div><div class="gift-sign">带着心意，${safe(sender)}</div></div></div><div class="gift-actions"><button class="btn primary" data-action="share">复制预览链接 ${arrow}</button><button class="btn secondary" data-action="download">↓ 下载歌曲</button></div><p class="gift-disclaimer">这是本地 UI 原型，链接仅在可访问本机的环境中有效。<br>正式礼物页将在部署后生成独立链接；获得链接的人可以访问。</p><div style="text-align:center"><a class="text-link" href="#create">我也想送出一首歌 ${arrow}</a></div></section>`;
}
async function handleAction(action){
 if(action==='home')return navigate('#home');
 if(action==='create'){state.step=0;return navigate('#create');}
 if(action==='gift-demo')return navigate('#gift');
 if(action==='play'||action==='download')return toast('这是 UI 原型，真实音频将在生成接口接入后提供。');
 if(action==='share'){try{await navigator.clipboard.writeText(location.href);toast('已复制本地预览链接，正式分享将在部署后开放。');}catch{toast('可复制浏览器地址栏，查看当前礼物页原型。');}return;}
 collect();
 if(action==='sample'){state.story='我们在大学吉他社认识。毕业后去了不同的城市，但一直约好要一起去海边看日出。她总会在我加班的时候提醒我吃饭，我想把这些小事唱给她听。';state.message='希望以后每个生日，都能一起度过。';renderCreate();return;}
 if(action==='back'){if(state.step>0){state.step--;renderCreate();}else navigate('#home');return;}
 if(action==='next'){
  if(state.step===0&&!state.recipient.trim()){toast('先写下 TA 的称呼吧。');document.querySelector('#recipient').focus();return;}
  if(state.step===1&&!state.story.trim()){toast('写下一小段回忆，让歌曲有你的故事。');document.querySelector('#story').focus();return;}
  if(state.step===3&&(!state.title.trim()||!state.lyrics.trim())){toast('请保留一个歌名和一段歌词。');return;}
  if(state.step<3){state.step++;if(state.step===3&&!state.lyrics)state.lyrics=demoLyrics;renderCreate();window.scrollTo({top:0,behavior:'smooth'});return;}
  const panel=document.querySelector('.form-panel');panel.innerHTML=`<div class="loading-view" role="status"><div class="loading-disc"><svg><use href="#wind"/></svg></div><div class="eyebrow" style="justify-content:center">YOUR STORY IS FINDING ITS MELODY</div><h2>让心意，慢慢长出旋律。</h2><p>正在演示歌曲生成的等待状态。<br>稍后将打开音乐礼物页示例。</p><div class="loading-dots">···</div><p class="prototype-note">此过程为交互演示，没有提交真实生成任务。</p></div>`;
  generationTimer=setTimeout(()=>{state.generated=true;navigate('#gift');},2200);
 }
}
document.addEventListener('click',event=>{
 const target=event.target.closest('button,a');if(!target)return;
 if(target.dataset.action){event.preventDefault();handleAction(target.dataset.action);return;}
 if(target.dataset.choice){collect();state[target.dataset.choice]=target.dataset.value;renderCreate();return;}
 if(target.dataset.occasion){state.occasion=target.dataset.occasion;state.step=0;navigate('#create');return;}
 if(target.dataset.example){state.recipient=target.dataset.example==='family'?'妈妈':'老朋友';state.title=target.dataset.example==='family'?'回家的那阵风':'风经过的夏天';state.lyrics=target.dataset.example==='family'?'门前的灯亮了一整晚\n你说饭菜凉了可以再暖\n我走过了那么多的远方\n最想念的还是家的模样':'那年蝉鸣里的愿望\n如今各自奔赴远方\n愿风替我捎去问候\n下一次见面再并肩歌唱';navigate('#gift');return;}
 if(target.dataset.scroll){event.preventDefault();const section=target.dataset.scroll;if(location.hash!=='#home'){navigate('#home');requestAnimationFrame(()=>document.getElementById(section)?.scrollIntoView({behavior:'smooth'}));}else document.getElementById(section)?.scrollIntoView({behavior:'smooth'});}
});
window.addEventListener('hashchange',render);render();
