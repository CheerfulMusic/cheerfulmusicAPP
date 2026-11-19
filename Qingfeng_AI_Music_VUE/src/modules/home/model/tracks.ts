import type { PlayableTrack } from '../../../shared/player/store'
import type { PublishedSong } from '../../artist/api/artist'
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
export interface PreviewTrack extends PlayableTrack { recipient: string; subtitle: string; style: string; occasion: string; description: string; quote: string; category: string; durationSeconds: number; filters: string[]; tags: string[]; authoredTitle?: boolean; genre?: string; delivery?: 'CDN'; lyrics?: string; publication?: { artistId: string; stageName: string; song: PublishedSong } }
export const previewFilters = [
  {id:'pop',label:'流行'},{id:'electronic',label:'电子'},{id:'rnb',label:'R&B / Soul'},
  {id:'indie',label:'独立'},{id:'rock',label:'摇滚'},{id:'hiphop',label:'嘻哈'},
  {id:'jazz',label:'爵士'},{id:'instrumental',label:'轻音乐'},
  {id:'folk',label:'民谣'},{id:'disco',label:'Disco'},{id:'guofeng',label:'古风'},{id:'house',label:'House'},
]
export const tracks: PreviewTrack[] = [
  { id: 'love', title: '把平凡唱给你', recipient: '爱人', subtitle: '写给相伴的爱人', category: '爱情', filters:['love'], tags:['爱情','温柔陪伴'], style: '爱情主题', occasion: '恋爱纪念', cover: '/media/explore-love-v1.webp', audio: '/media/sample-love-v2-preview-128.mp3', durationSeconds: 180.921333, provenance: 'SAMPLE', description: '把喜欢与陪伴，留在一首歌里。', quote: '想和你，把平凡的日子慢慢过成爱。' },
  // 桌面“走了那么远（流行）”与“家庭范例歌”字节相同，只展示一次并归入两个筛选。
  { id: 'family', title: '走了那么远', authoredTitle:true, recipient: '家人', subtitle: '写给牵挂你的家人', category: '家庭', genre:'流行', filters:['family','pop'], tags:['流行','家庭'], style: '抒情流行', occasion: '送给家人', cover: '/media/explore-family-v1.webp', audio: '/media/sample-family-v2-preview-128.mp3', durationSeconds: 162.507750, provenance: 'SAMPLE', description: '那些日常里的牵挂，值得被认真唱出来。', quote: '' },
  { id: 'travel', title: '去有海风的地方', recipient: '自己', subtitle: '写给向往自由的自己', category: '自由旅行', filters:['travel'], tags:['旅行','自由随性'], style: '自由随性 · 大海旅行', occasion: '送给自己', cover: '/media/explore-travel-v1.webp', audio: '/media/sample-ocean-travel-v2-preview-128.mp3', durationSeconds: 206.571200, provenance: 'SAMPLE', description: '跟着海风出发，把自由唱给自己。', quote: '下一站，去有海风的地方。' },
  { id:'red-guofeng',title:'红',authoredTitle:true,recipient:'',subtitle:'古风作品试听',category:'古风',filters:['guofeng'],tags:['古风','东方意境'],style:'国风古韵',occasion:'',cover:'/media/explore-guofeng-v1.svg',audio:'/media/sample-red-guofeng-v1-preview-128.mp3',durationSeconds:200.955200,provenance:'SAMPLE',description:'在东方意境里，感受古风音乐的韵味。',quote:'' },
  { id:'rain-disco',title:'忽然下起雨',authoredTitle:true,recipient:'',subtitle:'Disco作品试听',category:'Disco',filters:['disco'],tags:['Disco','复古律动'],style:'Disco舞曲',occasion:'',cover:'/media/explore-disco-v1.jpg',audio:'/media/sample-rain-disco-v1-preview-128.mp3',durationSeconds:94.759979,provenance:'SAMPLE',description:'跟着复古律动，换一种方式听见雨天。',quote:'' },
  { id:'tomorrow-rock',title:'明天再说',authoredTitle:true,recipient:'',subtitle:'摇滚作品试听',category:'摇滚',filters:['rock','indie'],tags:['摇滚','自由态度'],style:'独立摇滚',occasion:'',cover:'/media/explore-rock-v1.jpg',audio:'/media/sample-tomorrow-rock-v1-preview-128.mp3',durationSeconds:217.200000,provenance:'SAMPLE',description:'用摇滚表达态度，让情绪跟随节奏释放。',quote:'' },
  { id:'scenery-folk',title:'最美的风景',authoredTitle:true,recipient:'',subtitle:'民谣作品试听',category:'民谣',filters:['folk'],tags:['民谣','沿途风景'],style:'温暖民谣',occasion:'',cover:'/media/explore-family-v1.webp',audio:'/media/sample-scenery-folk-v1-preview-128.mp3',durationSeconds:110.832000,provenance:'SAMPLE',description:'用自然朴素的民谣，收藏沿途最美的风景。',quote:'' },
  { id:'hometown-electronic',title:'hometown lover',authoredTitle:true,recipient:'',subtitle:'电子作品试听',category:'电子',filters:['electronic'],tags:['电子','城市律动'],style:'梦幻电子',occasion:'',cover:'/media/explore-electronic-v1.jpg',audio:'/media/sample-hometown-electronic-v1-preview-128.mp3',durationSeconds:112.759979,provenance:'SAMPLE',description:'在电子律动里，寻找关于故乡与爱的想象。',quote:'' },
  { id:'river-instrumental',title:'When the River Breathes',authoredTitle:true,recipient:'',subtitle:'纯音乐作品试听',category:'纯音乐',filters:['instrumental'],tags:['纯音乐','静心放松'],style:'空灵氛围',occasion:'',cover:'/media/explore-instrumental-v1.jpg',audio:'/media/sample-river-instrumental-v1-preview-128.mp3',durationSeconds:197.999979,provenance:'SAMPLE',description:'让旋律像河流般舒展，留一段安静聆听的时间。',quote:'' },
  {
    "id": "feng-ye-xing",
    "title": "风夜行",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-feng-ye-xing-v1-cover.jpg",
    "audio": "/media/sample-feng-ye-xing-v1-preview-128.mp3",
    "durationSeconds": 212.52,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN"
  },
  {
    "id": "ming-chui-si-hai",
    "title": "灵玥 名垂四海",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-ming-chui-si-hai-v1-cover.jpg",
    "audio": "/media/sample-ming-chui-si-hai-v1-preview-128.mp3",
    "durationSeconds": 152.679979,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN"
  },
  {
    "id": "liu-lang-shi-ren",
    "title": "流浪诗人",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-liu-lang-shi-ren-v1-cover.jpg",
    "audio": "/media/sample-liu-lang-shi-ren-v1-preview-128.mp3",
    "durationSeconds": 140.808,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "verse\n把名字忘在陌生城市\n风吹过后 方才清醒\n拾起几段破碎的光影\n藏进袖口 聊以慰藉\npre chorus\n没等夜空 亮起繁星\n影子已经 替我前行 \n那些未说完的话 \n就写给远方的风景\nChorus\n我是流浪的诗人\n把孤独当做旅程\n不知去路 不求掌声\n随风带我走过一生\nChorus\n若命运于我失衡\n我便和苦难共生\n这一身风尘不是伤痕\n是我活过的明证\npre chorus\n没等夜空 亮起繁星\n影子已经 替我前行 \n那些未说完的话 \n就写给远方的风景\nChorus\n我是流浪的诗人\n把孤独当做旅程\n不知去路 不求掌声\n随风带我走过一生\nChorus\n若命运于我失衡\n我便和苦难共生\n这一身风尘不是伤痕\n是我活过的明证"
  },
  {
    "id": "luo-yu-de-cao-chang",
    "title": "落雨的操场",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-luo-yu-de-cao-chang-v1-cover.jpg",
    "audio": "/media/sample-luo-yu-de-cao-chang-v1-preview-128.mp3",
    "durationSeconds": 184.76,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "[Verse 1]\n操场边的水泥台阶落了灰\n风把白衬衫吹得像在后退\n翻开抽屉深处泛黄的信纸堆\n原来有些话注定来不及给\n\n[Verse 2]\n放学的单车骑过门口的石碑\n落日把影子拉长又慢慢揉碎\n并肩走过长长的一小段校门北\n谁先松开手都没说无所谓\n\n[Chorus]\n多想停在下过暴雨的操场\n看夕阳把我们投射在围墙\n你低着头踩着水洼慢慢晃\n我却始终不敢拉住你衣裳\n遗憾像断了线的纸鸢摇晃\n青春全是无处投递的过往\n我们再次回到了这个地方\n却回不到最初的模样\n\n[Verse]\n放学的单车骑过门口的石碑\n落日把影子拉长又慢慢揉碎\n并肩走过长长的一小段校门北\n谁先松开手都没说无所谓\n\n[Chorus]\n多想停在下过暴雨的操场\n看夕阳把我们投射在围墙\n你低着头踩着水洼慢慢晃\n我却始终不敢拉住你衣裳\n遗憾像断了线的纸鸢摇晃\n青春全是无处投递的过往\n我们再次回到了这个地方\n却回不到最初的模样"
  },
  {
    "id": "mei-jian-fu-yun",
    "title": "眉间浮云",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-mei-jian-fu-yun-v1-cover.jpg",
    "audio": "/media/sample-mei-jian-fu-yun-v1-preview-128.mp3",
    "durationSeconds": 194.0,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "[Verse 1]\n檐雨敲醒旧时的窗\n你把一盏灯留在远方\n纸上未干的半句寻常\n被风翻过几行\n\n[Pre-Chorus]\n若相逢注定要经过遗忘\n为何你的名字仍烫\n我沿着月色走过长巷\n听见心事回响\n\n[Chorus]\n一圈一圈岁月 落在掌心\n像你眉间未散的浮云\n我曾以为转身便能够平静\n却在梦醒时又看见你\n一程一程山水 隔开约定\n谁把旧梦藏进了琴音\n若来世还能在人海里相遇\n请你先唤我的姓名\n\n[Pre-Chorus]\n若相逢注定要经过遗忘\n为何你的名字仍烫\n我沿着月色走过长巷\n听见心事回响\n\n[Chorus]\n一圈一圈岁月 落在掌心\n像你眉间未散的浮云\n我曾以为转身便能够平静\n却在梦醒时又看见你\n一程一程山水 隔开约定\n谁把旧梦藏进了琴音\n若来世还能在人海里相遇\n请你先唤我的姓名"
  },
  {
    "id": "wu-ren-dai-ti",
    "title": "无人代替",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-wu-ren-dai-ti-v1-cover.jpg",
    "audio": "/media/sample-wu-ren-dai-ti-v1-preview-128.mp3",
    "durationSeconds": 183.6,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "[verse]\n\n街角的风又吹过那家店\n玻璃倒映着陌生的侧脸\n我们曾把永远说得很远\n却没能走过几个明天\n\n[prechorus]\n\n我以为时间会替我保管\n那些没说出口的遗憾\n可每当人海散了又聚\n总有个位置无人能代替\n\n[chorus]\n\n原来爱不是谁离开谁就能习惯\n只是把曾经变成偶尔的感叹\n我们在人群里交换着温暖\n却再也找不到当初的答案\n\n我走过几座城市 看过几场离散\n才明白有些人只适合做一段\n如果重逢只是为了说声平安\n那就让回忆停在最好的那晚\n\n[prechorus]\n\n我以为时间会替我保管\n那些没说出口的遗憾\n可每当人海散了又聚\n总有个位置无人能代替\n\n[chorus]\n\n原来爱不是谁离开谁就能习惯\n只是把曾经变成偶尔的感叹\n我们在人群里交换着温暖\n却再也找不到当初的答案\n\n我走过几座城市 看过几场离散\n才明白有些人只适合做一段\n如果重逢只是为了说声平安\n那就让回忆停在最好的那晚"
  },
  {
    "id": "wu-ren-qu",
    "title": "无人区",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-wu-ren-qu-v1-cover.jpg",
    "audio": "/media/sample-wu-ren-qu-v1-preview-128.mp3",
    "durationSeconds": 159.719979,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "《无人区》\nVerse\n晚风吹散了最后的余温\n地平线沉墨在天际\n我们像两颗失速的行星\n偏离轨道 不再靠近\nPre-Chorus 2\n我知道你已经决定 \n结果不必费心编辑\n告别是一场默片电影\n没声音才会让人窒息\nChorus\n这是只有我们的无人区\n在坠落之前已沉溺\n我伸手却没能抱紧你\n剩下冰冷的空气\nChorus\n这是只有我们的无人区\n留一片凄美的风景\n若结局注定要分离\n那就让梦 早点清醒\n\nPre-Chorus 2\n我知道你已经决定 \n结果不必费心编辑\n告别是一场默片电影\n没声音才会让人窒息\nChorus\n这是只有我们的无人区\n在坠落之前已沉溺\n我伸手却没能抱紧你\n剩下冰冷的空气\nChorus\n这是只有我们的无人区\n留一片凄美的风景\n若结局注定要分离\n那就让梦 早点清醒\n早点清醒\n早点清醒"
  },
  {
    "id": "wu-ye-zhi-shi",
    "title": "午夜之时",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "华语流行",
    "category": "华语流行",
    "filters": [
      "pop"
    ],
    "tags": [
      "华语流行"
    ],
    "style": "华语流行",
    "occasion": "",
    "cover": "/media/sample-wu-ye-zhi-shi-v1-cover.jpg",
    "audio": "/media/sample-wu-ye-zhi-shi-v1-preview-128.mp3",
    "durationSeconds": 162.648,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "Verse1\n当月光洒满褪色台阶时\n苏醒吧 沉睡的神执\n秘银纹路爬上右手食指\n叫我重新命名尘世\nVerse2\n水晶球凝结玫瑰的荆棘\n预言在现实中位移\n偷走占卜师未知的骤雨\n浇灌左肩新生蝶翅\npre-chorus\n羊皮卷悄声 翻译着\n古堡残垣的 叙事诗\n未完成的结局\n在塔尖悬置\nchorus\n我是突然被选中的开始\n闯入星象开裂的午夜之时\n咒语在血管里写成韵脚\n每个字都折射万种启示\nchorus\n我是突然被选中的开始\n重设所有失控的施法位置\n时间在掌心弯曲成残月\n终于完成这黑暗的仪式"
  },
  {
    "id": "meng-de-chi-bang",
    "title": "梦的翅膀受了伤",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "电子",
    "category": "电子",
    "filters": [
      "electronic"
    ],
    "tags": [
      "电子"
    ],
    "style": "电子",
    "occasion": "",
    "cover": "/media/sample-meng-de-chi-bang-v1-cover.jpg",
    "audio": "/media/sample-meng-de-chi-bang-v1-preview-128.mp3",
    "durationSeconds": 154.08,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN"
  },
  {
    "id": "after-midnight",
    "title": "AFTER MIDNIGHT",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "欧美流行",
    "category": "欧美流行",
    "filters": [
      "pop",
      "global-pop"
    ],
    "tags": [
      "欧美流行"
    ],
    "style": "欧美流行",
    "occasion": "",
    "cover": "/media/sample-after-midnight-v1-cover.jpg",
    "audio": "/media/sample-after-midnight-v1-preview-128.mp3",
    "durationSeconds": 203.84,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN"
  },
  {
    "id": "if-you-wanna-feel-me",
    "title": "if you wanna feel me",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "欧美流行",
    "category": "欧美流行",
    "filters": [
      "pop",
      "global-pop"
    ],
    "tags": [
      "欧美流行"
    ],
    "style": "欧美流行",
    "occasion": "",
    "cover": "/media/sample-if-you-wanna-feel-me-v1-cover.jpg",
    "audio": "/media/sample-if-you-wanna-feel-me-v1-preview-128.mp3",
    "durationSeconds": 142.599979,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "If you wanna feel me\nDo it when you're down (baby)\nDo it on the town\nDo you wanna heal me\nDo it on the ground (baby)\nDo you wanna feel me (yeah)\nDo it when you're down\n\nLet it fall\nForget about the sun\nForget about the sun\nLet it fall\nForget about the sun\nForget about the sun\n\nIf you wanna feel me\nDo it when you're down (baby)\nIf you see me do it in the town\nIf you wanna heal me\nDo it on the ground (baby)\nIf you wanna feel me (yeah)\nDo it when you're down\n\nLet it fall\nForget about the sun (yeah)\nForget about the sun\nLet it fall\nForget about the sun (yeah)\nForget about the sun\n\nLet it fall\nForget about the sun (yeah)\nForget about the sun\nLet it fall\nForget about the sun (yeah)\nForget about the sun"
  },
  {
    "id": "where-the-wind-goes",
    "title": "Where the Wind Goes",
    "authoredTitle": true,
    "recipient": "",
    "subtitle": "欧美流行",
    "category": "欧美流行",
    "filters": [
      "pop",
      "global-pop"
    ],
    "tags": [
      "欧美流行"
    ],
    "style": "欧美流行",
    "occasion": "",
    "cover": "/media/sample-where-the-wind-goes-v1-cover.jpg",
    "audio": "/media/sample-where-the-wind-goes-v1-preview-128.mp3",
    "durationSeconds": 137.92,
    "provenance": "SAMPLE",
    "description": "",
    "quote": "",
    "delivery": "CDN",
    "lyrics": "（verse）\nYour silhouette against the blue\nA thousand miles between us two\nI hear your voice inside the breeze\nLike summer dancing through the trees\n\n(Pre-Chorus)\n\nAnd I don't know where we're going\nI just know we can't stay\nThere's a thousand roads before us\nAnd the ocean in our way\n\n(Chorus)\n\nSo let the tradewinds carry me\nAcross the endless sea\nWhere the stars fall down like memories\nAnd everything is free\n\nOh, we were young, we were wild\nLike the waves beneath the sky\nIf I ever lose my way\nI'll follow where the wind goes tonight\n\n(Pre-Chorus)\n\nAnd I don't know where we're going\nI just know we can't stay\nThere's a thousand roads before us\nAnd the ocean in our way\n\n(Chorus)\n\nSo let the tradewinds carry me\nAcross the endless sea\nWhere the stars fall down like memories\nAnd everything is free\n\nOh, we were young, we were wild\nLike the waves beneath the sky\nIf I ever lose my way\nI'll follow where the wind goes tonight"
  },
  {"id": "body-language", "title": "Body Language", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/body-language-c251e80045fe-cover.jpg", "audio": "/media/recommendations/body-language-8e59749c37e9-preview-128.mp3", "durationSeconds": 165.96, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "Body language\n\n[Intro - Whispered Female Vocal]\nDon't say a word\nJust move\nJust move\n\n[Verse - Rhythmic Spoken Vocal]\nI see you looking from across the room\nYou know exactly what I'm gonna do\nNo conversation, no introduction\nJust let the rhythm take control of you\n\n[Pre-Drop - Filtered Vocal]\nI don't need your name\nI don't need your number\nLet your body talk\nTake me somewhere under\n\n[Drop - Chopped Vocal Hook]\nBody language\nBody, body language\nDon't talk, just dance\nBody, body language\n\n[Instrumental Drop - Heavy Bass Groove]\n\n[Breakdown - Whispered]\nNo words tonight\nJust you and I\nFeel the bass\nFeel the vibe\n\n[Build-Up]\nLet your body\nLet your body\nLet your body talk\n\n[Final Drop]\nBody language\nBody, body language\nDon't talk, just dance\nBody, body language\n\n[Extended Instrumental Outro]"},
  {"id": "into-the-sun", "title": "Into the Sun", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/into-the-sun-297c430b1904-cover.jpg", "audio": "/media/recommendations/into-the-sun-70d136c3fe1d-preview-128.mp3", "durationSeconds": 188.72, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "Into the Sun\n\n[Intro - Atmospheric Vocal]\nOh, we rise\nInto the sun\nInto the sun\n[Verse - Soulful]\nI’ve been searching for a place\nWhere the ocean meets the sky\nEvery shadow fades away\nWhen I see you in the light\n[Pre-Chorus - Emotional Build]\nTake my hand\nDon’t let go\nFeel the rhythm\nLet it flow\n[Chorus - Hypnotic Chant]\nInto the sun\nWe keep on running\nInto the sun\nA new beginning\nOh, we rise\nOh, we rise\nInto the sun\nInto the sun\n[Instrumental Drop - Organic Percussion]\n[Bridge - Minimal]\nWhen the world is falling down\nWe can turn it all around\nFeel the earth beneath our feet\nLet the music set us free\n[Final Chorus]\nInto the sun\nWe keep on running\nInto the sun\nA new beginning\n[Extended Instrumental Outro]"},
  {"id": "one-more-night", "title": "One More Night", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/one-more-night-f04f4723634a-cover.jpg", "audio": "/media/recommendations/one-more-night-3e375188eafa-preview-128.mp3", "durationSeconds": 173.16, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro - Soft Male Vocal]\nOne more night\nJust one more night\nWith you\n[Verse 1]\nThe city sleeps but we’re still wide awake\nAnother chance, another chance to take\nYour silhouette beneath the neon lights\nI don’t wanna say goodbye tonight\n[Pre-Chorus]\nIf tomorrow never comes\nWould you stay until the sun?\nWe don’t need a reason why\nJust another night\n[Chorus - Emotional Melodic Hook]\nGive me one more night\nOne more night with you\nDancing through the lights\nLike we used to do\nGive me one more night\nBefore we say goodbye\nWe can make forever\nLast until the morning light\n[Instrumental Drop - Melodic Synth Hook]\n[Verse 2]\nThe music fades but I can feel you near\nEvery moment makes it crystal clear\nWe both know this can’t go on forever\nBut tonight we’re still together\n[Final Chorus]\nGive me one more night\nOne more night with you\nDancing through the lights\nLike we used to do\n[Outro - Vocal Echo]\nOne more night\nOne more night\nWith you"},
  {"id": "all-night-one-more-time", "title": "All Night One More Time", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/all-night-one-more-time-2a75588e032c-cover.jpg", "audio": "/media/recommendations/all-night-one-more-time-2f881a6c54ab-preview-128.mp3", "durationSeconds": 162.8, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nMove close, stay low\nLet the rhythm take control\n\n[Hook]\nAll night, one more time\nHold that heat, cross the line\nAll night, one more time\nFeel it hit, feel it ride\n\n[Drop]\nOne more time\nAll night\nOne more time\nAll night\n\n[Breakdown]\nNo talking, just a look\nYou already know the rules\nCome closer, take your time\nWe can lose track of the night\n\n[Build]\nAll night\nOne more time\nAll night\nOne more time\n\n[Drop]\nOne more time\nAll night\nHold that heat, cross the line\nOne more time\nAll night\nFeel it hit, feel it ride\n\n[Outro]\nAll night\nOne more time"},
  {"id": "euphoric-drop", "title": "Euphoric Drop", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic", "instrumental"], "tags": ["电子", "纯音乐"], "style": "电子", "occasion": "", "cover": "/media/recommendations/euphoric-drop-ded7c496d5d0-cover.jpg", "audio": "/media/recommendations/euphoric-drop-bceceadc198d-preview-128.mp3", "durationSeconds": 202.68, "provenance": "SAMPLE", "description": "", "quote": ""},
  {"id": "i-run-this-show", "title": "I Run This Show", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/i-run-this-show-568bd52479f8-cover.jpg", "audio": "/media/recommendations/i-run-this-show-3c234aa9e239-preview-128.mp3", "durationSeconds": 171.12, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro: whispered]\nCome a little closer\nKeep your eyes on me\nWhen the room goes quiet\nYou know where to be\n\n[Build]\nOne step, lean in\nTwo hands, don’t blink\nHold that look, hold that line\nYou can have this moment if you move on time\n\n[Chorus]\nGet close, get close, don’t let go\nRight here, right now, take it slow\nGet close, get close, lose control\nYou know, you know, I run this show\n\n[Drop]\nGet close, get close\nI run this show\nGet close, get close\nDon’t let go\n\n[Verse]\nRed lips, cool nerve\nI decide what you deserve\nSay my name, then wait your turn\nWatch me make the whole place burn\n\n[Build]\nOne step, lean in\nTwo hands, don’t blink\nHold that look, hold that line\nYou can have this moment if you move on time\n\n[Chorus]\nGet close, get close, don’t let go\nRight here, right now, take it slow\nGet close, get close, lose control\nYou know, you know, I run this show\n\n[Drop]\nGet close, get close\nI run this show\nGet close, get close\nDon’t let go\n\n[Outro: whispered]\nCome a little closer\nYou know where to be"},
  {"id": "light-it-up", "title": "LIGHT IT UP", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/light-it-up-65eecb7659b8-cover.jpg", "audio": "/media/recommendations/light-it-up-14f42b2a754b-preview-128.mp3", "durationSeconds": 209.08, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Verse 1]\nUnderneath the neon sky\nI can feel the fire inside\nEvery heartbeat takes me higher\nWe're electric, we're on fire\n\nNo more waiting for the sun\nTonight we are the chosen ones\nLeave the shadows far behind\nLet the universe collide\n\n[Pre-Chorus]\nTake me higher, higher\nSet my soul on fire\nCan you feel it coming?\nWe're never coming down\n\n[Build Up – Rising Vocal]\nThree, two, one...\nLET'S GO!\n\n[Drop – Big Festival Synth]\nLight it up, up, up!\nWe're burning through the night\nLight it up, up, up!\nWe're taking over the sky\n\nOh-oh-oh-oh!\nWe will never stop\nOh-oh-oh-oh!\nTurn it up, light it up!\n\n[Verse 2]\nFeel the rhythm in your veins\nWash away the tears and pain\nEvery moment, every sound\nWe are lost and we are found\n\n[Pre-Chorus]\nTake me higher, higher\nSet my soul on fire\nCan you feel it coming?\nWe're never coming down\n\n[Build Up – Vocal Chops]\nHigher!\nHigher!\nAre you ready?\nLET'S GO!\n\n[Drop – Explosive EDM]\nLight it up, up, up!\nWe're burning through the night\nLight it up, up, up!\nWe're taking over the sky\n\nOh-oh-oh-oh!\nWe will never stop\nOh-oh-oh-oh!\nTurn it up, light it up!"},
  {"id": "cant-stop-this-feeling", "title": "Can't Stop This Feeling", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/cant-stop-this-feeling-10ead733a829-cover.jpg", "audio": "/media/recommendations/cant-stop-this-feeling-34244d3c6efc-preview-128.mp3", "durationSeconds": 156.4, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh-oh, yeah\nI can’t stop, I can’t stop\nOh-oh\n[Verse 1]\nWoke up with the sunlight on my face\nGot a little rhythm running through my veins\nNo plans, no worries, no place to be\nTonight the whole world’s waiting on me\nGot my friends all calling on the phone\nBut I don’t wanna spend another night alone\nTurn it up, let the speakers blow\nThere’s only one direction that I wanna go\n[Pre-Chorus]\nHands up, we’re getting closer\nOne touch, the night takes over\nDon’t think, just let it happen\nI feel it coming, coming, coming\n[Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\nI can’t stop this feeling\nThe room keeps spinning\nWe got the whole night, whole night\nAnd we’re just getting started\n[Post-Chorus]\nOh-oh-oh, can’t stop now\nOh-oh-oh, don’t slow down\nOh-oh-oh, feel that sound\nWe ain’t coming down\n[Verse 2]\nEverybody moving like a movie scene\nYour smile got me somewhere in between\nOne more drink, one more song\nI don’t wanna know where the night goes wrong\n[Pre-Chorus]\nHands up, we’re getting closer\nOne touch, the night takes over\nDon’t think, just let it happen\nI feel it coming, coming, coming\n[Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\n[Bridge]\nNo tomorrow, no regrets\nThis is something we won’t forget\nWhen the morning comes around\nWe’ll still be dancing to this sound\n[Final Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\n[Outro]\nOh-oh-oh, can’t stop now\nOh-oh-oh, don’t slow down"},
  {"id": "midnight-call", "title": "Midnight Call", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/midnight-call-2a951cfbf59c-cover.jpg", "audio": "/media/recommendations/midnight-call-8a6cdb33c69e-preview-128.mp3", "durationSeconds": 127.96, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh-oh, oh-oh\nIt’s another midnight call\nYeah\n[Verse 1]\n2 AM, your name lights up my phone\nSaid you’re outside, don’t wanna be alone\nI know we said that we were better off\nBut here we are, right back where we started\nYou got that look I can’t ignore\nBarefoot dancing on my bedroom floor\nWe keep on saying this is the last time\nBut we both know that’s another lie\n[Pre-Chorus]\nAnd I don’t wanna think too much\nWhen you’re this close, when you’re this close\nMaybe we’re just bad at love\nBut I can’t let you go\n[Chorus]\nIt’s a midnight call\nAnd I’m coming over\nEven if we fall\nEven if it’s over\nYou know I can’t resist\nThe way you pull me closer\nIt’s a midnight call\nAnd I still want you sober\n[Post-Chorus]\nOh-oh, don’t let me go\nOh-oh, you already know\nIt’s a midnight call\nIt’s a midnight call\n[Verse 2]\nBlue lights dancing on the avenue\nEvery little thing reminds me of you\nWe could be strangers in another life\nBut tonight, you’re still mine\n[Final Chorus]\nIt’s a midnight call\nAnd I’m coming over\nEven if we fall\nEven if it’s over\n[Outro]\nIt’s a midnight call\nI still want you close"},
  {"id": "summer-on-repeat", "title": "Summer on Repeat", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/summer-on-repeat-d8a226ab9537-cover.jpg", "audio": "/media/recommendations/summer-on-repeat-68e4b531c543-preview-128.mp3", "durationSeconds": 171.16, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh yeah\nSummer on repeat\nOne more time\n[Verse 1]\nWindows down on the highway\nYour sunglasses on my face\nWe got nowhere we gotta be\nJust you and me and the summer breeze\nRadio playing our favorite song\nSinging every word with the windows on\nThe sun goes down but we don’t care\nThere’s magic in the midnight air\n[Pre-Chorus]\nDon’t wanna go home\nDon’t wanna slow down\nLet’s keep this feeling\nUntil the sun comes around\n[Chorus]\nPut the summer on repeat\nDancing barefoot in the street\nEvery little memory\nFeels like a movie scene\nWe don’t ever need to sleep\nGot the whole world underneath\nYou and I, just you and me\nPut the summer on repeat\n[Post-Chorus]\nOh-oh-oh, on repeat\nOh-oh-oh, you and me\nOh-oh-oh, on repeat\nPut the summer on repeat\n[Verse 2]\nLate night drives and ocean views\nEvery little road leads back to you\nWe got a thousand miles to go\nBut I don’t wanna take it slow\n[Bridge]\nIf the summer disappears\nI’ll remember you were here\nEven when the seasons change\nWe’ll be dancing just the same\n[Final Chorus]\nPut the summer on repeat\nDancing barefoot in the street\nEvery little memory\nFeels like a movie scene\n[Outro]\nOn repeat\nYou and me\nSummer on repeat"},
]

// Only these built-in demos are translated. User-created music keeps its authored title.
export function usePreviewTracks() {
  return computed(() => tracks.map(track => ({ ...track, titleKey: track.authoredTitle?undefined:track.title,
    title: track.authoredTitle?track.title:t(track.title), recipient: t(track.recipient), subtitle: t(track.subtitle),tags:track.tags.map(tag=>t(tag)),
    style: t(track.style), description: t(track.description), quote: t(track.quote), category: t(track.category),genre:t(track.genre||track.category),
  })))
}
