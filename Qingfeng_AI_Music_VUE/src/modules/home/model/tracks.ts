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
  { id: 'love', title: '把平凡唱给你', recipient: '爱人', subtitle: '写给相伴的爱人', category: '爱情', filters:['love','pop'], tags:['爱情','温柔陪伴'], style: '爱情主题', occasion: '恋爱纪念', cover: '/media/explore-love-v1.webp', audio: '/media/sample-love-v2-preview-128.mp3', durationSeconds: 180.921333, provenance: 'SAMPLE', description: '把喜欢与陪伴，留在一首歌里。', quote: '想和你，把平凡的日子慢慢过成爱。', delivery: 'CDN' },
  // 桌面“走了那么远（流行）”与“家庭范例歌”字节相同，只展示一次并归入两个筛选。
  { id: 'family', title: '走了那么远', authoredTitle:true, recipient: '家人', subtitle: '写给牵挂你的家人', category: '家庭', genre:'流行', filters:['family','pop'], tags:['流行','家庭'], style: '抒情流行', occasion: '送给家人', cover: '/media/explore-family-v1.webp', audio: '/media/sample-family-v2-preview-128.mp3', durationSeconds: 162.507750, provenance: 'SAMPLE', description: '那些日常里的牵挂，值得被认真唱出来。', quote: '', delivery: 'CDN' },
  { id: 'travel', title: '去有海风的地方', recipient: '自己', subtitle: '写给向往自由的自己', category: '自由旅行', filters:['travel','pop'], tags:['旅行','自由随性'], style: '自由随性 · 大海旅行', occasion: '送给自己', cover: '/media/explore-travel-v1.webp', audio: '/media/sample-ocean-travel-v2-preview-128.mp3', durationSeconds: 206.571200, provenance: 'SAMPLE', description: '跟着海风出发，把自由唱给自己。', quote: '下一站，去有海风的地方。', delivery: 'CDN' },
  { id:'red-guofeng',title:'红',authoredTitle:true,recipient:'',subtitle:'古风作品试听',category:'古风',filters:['guofeng','pop'],tags:['古风','东方意境'],style:'国风古韵',occasion:'',cover:'/media/explore-guofeng-v1.svg',audio:'/media/sample-red-guofeng-v1-preview-128.mp3',durationSeconds:200.955200,provenance:'SAMPLE',description:'在东方意境里，感受古风音乐的韵味。',quote:'', delivery: 'CDN' },
  { id:'rain-disco',title:'忽然下起雨',authoredTitle:true,recipient:'',subtitle:'Disco作品试听',category:'Disco',filters:['disco','electronic'],tags:['Disco','复古律动'],style:'Disco舞曲',occasion:'',cover:'/media/explore-disco-v1.jpg',audio:'/media/sample-rain-disco-v1-preview-128.mp3',durationSeconds:94.759979,provenance:'SAMPLE',description:'跟着复古律动，换一种方式听见雨天。',quote:'', delivery: 'CDN' },
  { id:'tomorrow-rock',title:'明天再说',authoredTitle:true,recipient:'',subtitle:'摇滚作品试听',category:'摇滚',filters:['rock','indie','electronic'],tags:['摇滚','自由态度'],style:'独立摇滚',occasion:'',cover:'/media/explore-rock-v1.jpg',audio:'/media/sample-tomorrow-rock-v1-preview-128.mp3',durationSeconds:217.200000,provenance:'SAMPLE',description:'用摇滚表达态度，让情绪跟随节奏释放。',quote:'', delivery: 'CDN' },
  { id:'scenery-folk',title:'最美的风景',authoredTitle:true,recipient:'',subtitle:'民谣作品试听',category:'民谣',filters:['folk','pop'],tags:['民谣','沿途风景'],style:'温暖民谣',occasion:'',cover:'/media/explore-family-v1.webp',audio:'/media/sample-scenery-folk-v1-preview-128.mp3',durationSeconds:110.832000,provenance:'SAMPLE',description:'用自然朴素的民谣，收藏沿途最美的风景。',quote:'', delivery: 'CDN' },
  { id:'hometown-electronic',title:'hometown lover',authoredTitle:true,recipient:'',subtitle:'电子作品试听',category:'电子',filters:['electronic'],tags:['电子','城市律动'],style:'梦幻电子',occasion:'',cover:'/media/explore-electronic-v1.jpg',audio:'/media/sample-hometown-electronic-v1-preview-128.mp3',durationSeconds:112.759979,provenance:'SAMPLE',description:'在电子律动里，寻找关于故乡与爱的想象。',quote:'', delivery: 'CDN' },
  { id:'river-instrumental',title:'When the River Breathes',authoredTitle:true,recipient:'',subtitle:'纯音乐作品试听',category:'纯音乐',filters:['instrumental','pop'],tags:['纯音乐','静心放松'],style:'空灵氛围',occasion:'',cover:'/media/explore-instrumental-v1.jpg',audio:'/media/sample-river-instrumental-v1-preview-128.mp3',durationSeconds:197.999979,provenance:'SAMPLE',description:'让旋律像河流般舒展，留一段安静聆听的时间。',quote:'', delivery: 'CDN' },
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
  {"id": "body-language", "title": "Body Language", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/body-language-c251e80045fe-cover.jpg", "audio": "/media/recommendations/body-language-8e59749c37e9-preview-128.mp3", "durationSeconds": 165.96, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "Body language\n\n[Intro - Whispered Female Vocal]\nDon't say a word\nJust move\nJust move\n\n[Verse - Rhythmic Spoken Vocal]\nI see you looking from across the room\nYou know exactly what I'm gonna do\nNo conversation, no introduction\nJust let the rhythm take control of you\n\n[Pre-Drop - Filtered Vocal]\nI don't need your name\nI don't need your number\nLet your body talk\nTake me somewhere under\n\n[Drop - Chopped Vocal Hook]\nBody language\nBody, body language\nDon't talk, just dance\nBody, body language\n\n[Instrumental Drop - Heavy Bass Groove]\n\n[Breakdown - Whispered]\nNo words tonight\nJust you and I\nFeel the bass\nFeel the vibe\n\n[Build-Up]\nLet your body\nLet your body\nLet your body talk\n\n[Final Drop]\nBody language\nBody, body language\nDon't talk, just dance\nBody, body language\n\n[Extended Instrumental Outro]", "delivery": "CDN"},
  {"id": "into-the-sun", "title": "Into the Sun", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/into-the-sun-297c430b1904-cover.jpg", "audio": "/media/recommendations/into-the-sun-70d136c3fe1d-preview-128.mp3", "durationSeconds": 188.72, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "Into the Sun\n\n[Intro - Atmospheric Vocal]\nOh, we rise\nInto the sun\nInto the sun\n[Verse - Soulful]\nI’ve been searching for a place\nWhere the ocean meets the sky\nEvery shadow fades away\nWhen I see you in the light\n[Pre-Chorus - Emotional Build]\nTake my hand\nDon’t let go\nFeel the rhythm\nLet it flow\n[Chorus - Hypnotic Chant]\nInto the sun\nWe keep on running\nInto the sun\nA new beginning\nOh, we rise\nOh, we rise\nInto the sun\nInto the sun\n[Instrumental Drop - Organic Percussion]\n[Bridge - Minimal]\nWhen the world is falling down\nWe can turn it all around\nFeel the earth beneath our feet\nLet the music set us free\n[Final Chorus]\nInto the sun\nWe keep on running\nInto the sun\nA new beginning\n[Extended Instrumental Outro]", "delivery": "CDN"},
  {"id": "one-more-night", "title": "One More Night", "authoredTitle": true, "recipient": "", "subtitle": "House", "category": "House", "filters": ["house", "electronic"], "tags": ["House"], "style": "House", "occasion": "", "cover": "/media/recommendations/one-more-night-f04f4723634a-cover.jpg", "audio": "/media/recommendations/one-more-night-3e375188eafa-preview-128.mp3", "durationSeconds": 173.16, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro - Soft Male Vocal]\nOne more night\nJust one more night\nWith you\n[Verse 1]\nThe city sleeps but we’re still wide awake\nAnother chance, another chance to take\nYour silhouette beneath the neon lights\nI don’t wanna say goodbye tonight\n[Pre-Chorus]\nIf tomorrow never comes\nWould you stay until the sun?\nWe don’t need a reason why\nJust another night\n[Chorus - Emotional Melodic Hook]\nGive me one more night\nOne more night with you\nDancing through the lights\nLike we used to do\nGive me one more night\nBefore we say goodbye\nWe can make forever\nLast until the morning light\n[Instrumental Drop - Melodic Synth Hook]\n[Verse 2]\nThe music fades but I can feel you near\nEvery moment makes it crystal clear\nWe both know this can’t go on forever\nBut tonight we’re still together\n[Final Chorus]\nGive me one more night\nOne more night with you\nDancing through the lights\nLike we used to do\n[Outro - Vocal Echo]\nOne more night\nOne more night\nWith you", "delivery": "CDN"},
  {"id": "all-night-one-more-time", "title": "All Night One More Time", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/all-night-one-more-time-2a75588e032c-cover.jpg", "audio": "/media/recommendations/all-night-one-more-time-2f881a6c54ab-preview-128.mp3", "durationSeconds": 162.8, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nMove close, stay low\nLet the rhythm take control\n\n[Hook]\nAll night, one more time\nHold that heat, cross the line\nAll night, one more time\nFeel it hit, feel it ride\n\n[Drop]\nOne more time\nAll night\nOne more time\nAll night\n\n[Breakdown]\nNo talking, just a look\nYou already know the rules\nCome closer, take your time\nWe can lose track of the night\n\n[Build]\nAll night\nOne more time\nAll night\nOne more time\n\n[Drop]\nOne more time\nAll night\nHold that heat, cross the line\nOne more time\nAll night\nFeel it hit, feel it ride\n\n[Outro]\nAll night\nOne more time", "delivery": "CDN"},
  {"id": "euphoric-drop", "title": "Euphoric Drop", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic", "instrumental"], "tags": ["电子", "纯音乐"], "style": "电子", "occasion": "", "cover": "/media/recommendations/euphoric-drop-ded7c496d5d0-cover.jpg", "audio": "/media/recommendations/euphoric-drop-bceceadc198d-preview-128.mp3", "durationSeconds": 202.68, "provenance": "SAMPLE", "description": "", "quote": "", "delivery": "CDN"},
  {"id": "i-run-this-show", "title": "I Run This Show", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/i-run-this-show-568bd52479f8-cover.jpg", "audio": "/media/recommendations/i-run-this-show-3c234aa9e239-preview-128.mp3", "durationSeconds": 171.12, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro: whispered]\nCome a little closer\nKeep your eyes on me\nWhen the room goes quiet\nYou know where to be\n\n[Build]\nOne step, lean in\nTwo hands, don’t blink\nHold that look, hold that line\nYou can have this moment if you move on time\n\n[Chorus]\nGet close, get close, don’t let go\nRight here, right now, take it slow\nGet close, get close, lose control\nYou know, you know, I run this show\n\n[Drop]\nGet close, get close\nI run this show\nGet close, get close\nDon’t let go\n\n[Verse]\nRed lips, cool nerve\nI decide what you deserve\nSay my name, then wait your turn\nWatch me make the whole place burn\n\n[Build]\nOne step, lean in\nTwo hands, don’t blink\nHold that look, hold that line\nYou can have this moment if you move on time\n\n[Chorus]\nGet close, get close, don’t let go\nRight here, right now, take it slow\nGet close, get close, lose control\nYou know, you know, I run this show\n\n[Drop]\nGet close, get close\nI run this show\nGet close, get close\nDon’t let go\n\n[Outro: whispered]\nCome a little closer\nYou know where to be", "delivery": "CDN"},
  {"id": "light-it-up", "title": "LIGHT IT UP", "authoredTitle": true, "recipient": "", "subtitle": "电子", "category": "电子", "filters": ["electronic"], "tags": ["电子"], "style": "电子", "occasion": "", "cover": "/media/recommendations/light-it-up-65eecb7659b8-cover.jpg", "audio": "/media/recommendations/light-it-up-14f42b2a754b-preview-128.mp3", "durationSeconds": 209.08, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Verse 1]\nUnderneath the neon sky\nI can feel the fire inside\nEvery heartbeat takes me higher\nWe're electric, we're on fire\n\nNo more waiting for the sun\nTonight we are the chosen ones\nLeave the shadows far behind\nLet the universe collide\n\n[Pre-Chorus]\nTake me higher, higher\nSet my soul on fire\nCan you feel it coming?\nWe're never coming down\n\n[Build Up – Rising Vocal]\nThree, two, one...\nLET'S GO!\n\n[Drop – Big Festival Synth]\nLight it up, up, up!\nWe're burning through the night\nLight it up, up, up!\nWe're taking over the sky\n\nOh-oh-oh-oh!\nWe will never stop\nOh-oh-oh-oh!\nTurn it up, light it up!\n\n[Verse 2]\nFeel the rhythm in your veins\nWash away the tears and pain\nEvery moment, every sound\nWe are lost and we are found\n\n[Pre-Chorus]\nTake me higher, higher\nSet my soul on fire\nCan you feel it coming?\nWe're never coming down\n\n[Build Up – Vocal Chops]\nHigher!\nHigher!\nAre you ready?\nLET'S GO!\n\n[Drop – Explosive EDM]\nLight it up, up, up!\nWe're burning through the night\nLight it up, up, up!\nWe're taking over the sky\n\nOh-oh-oh-oh!\nWe will never stop\nOh-oh-oh-oh!\nTurn it up, light it up!", "delivery": "CDN"},
  {"id": "cant-stop-this-feeling", "title": "Can't Stop This Feeling", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/cant-stop-this-feeling-10ead733a829-cover.jpg", "audio": "/media/recommendations/cant-stop-this-feeling-34244d3c6efc-preview-128.mp3", "durationSeconds": 156.4, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh-oh, yeah\nI can’t stop, I can’t stop\nOh-oh\n[Verse 1]\nWoke up with the sunlight on my face\nGot a little rhythm running through my veins\nNo plans, no worries, no place to be\nTonight the whole world’s waiting on me\nGot my friends all calling on the phone\nBut I don’t wanna spend another night alone\nTurn it up, let the speakers blow\nThere’s only one direction that I wanna go\n[Pre-Chorus]\nHands up, we’re getting closer\nOne touch, the night takes over\nDon’t think, just let it happen\nI feel it coming, coming, coming\n[Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\nI can’t stop this feeling\nThe room keeps spinning\nWe got the whole night, whole night\nAnd we’re just getting started\n[Post-Chorus]\nOh-oh-oh, can’t stop now\nOh-oh-oh, don’t slow down\nOh-oh-oh, feel that sound\nWe ain’t coming down\n[Verse 2]\nEverybody moving like a movie scene\nYour smile got me somewhere in between\nOne more drink, one more song\nI don’t wanna know where the night goes wrong\n[Pre-Chorus]\nHands up, we’re getting closer\nOne touch, the night takes over\nDon’t think, just let it happen\nI feel it coming, coming, coming\n[Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\n[Bridge]\nNo tomorrow, no regrets\nThis is something we won’t forget\nWhen the morning comes around\nWe’ll still be dancing to this sound\n[Final Chorus]\nI can’t stop this feeling\nMy heart keeps beating\nWe got the whole night, whole night\nAnd I don’t need a reason\n[Outro]\nOh-oh-oh, can’t stop now\nOh-oh-oh, don’t slow down", "delivery": "CDN"},
  {"id": "midnight-call", "title": "Midnight Call", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/midnight-call-2a951cfbf59c-cover.jpg", "audio": "/media/recommendations/midnight-call-8a6cdb33c69e-preview-128.mp3", "durationSeconds": 127.96, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh-oh, oh-oh\nIt’s another midnight call\nYeah\n[Verse 1]\n2 AM, your name lights up my phone\nSaid you’re outside, don’t wanna be alone\nI know we said that we were better off\nBut here we are, right back where we started\nYou got that look I can’t ignore\nBarefoot dancing on my bedroom floor\nWe keep on saying this is the last time\nBut we both know that’s another lie\n[Pre-Chorus]\nAnd I don’t wanna think too much\nWhen you’re this close, when you’re this close\nMaybe we’re just bad at love\nBut I can’t let you go\n[Chorus]\nIt’s a midnight call\nAnd I’m coming over\nEven if we fall\nEven if it’s over\nYou know I can’t resist\nThe way you pull me closer\nIt’s a midnight call\nAnd I still want you sober\n[Post-Chorus]\nOh-oh, don’t let me go\nOh-oh, you already know\nIt’s a midnight call\nIt’s a midnight call\n[Verse 2]\nBlue lights dancing on the avenue\nEvery little thing reminds me of you\nWe could be strangers in another life\nBut tonight, you’re still mine\n[Final Chorus]\nIt’s a midnight call\nAnd I’m coming over\nEven if we fall\nEven if it’s over\n[Outro]\nIt’s a midnight call\nI still want you close", "delivery": "CDN"},
  {"id": "summer-on-repeat", "title": "Summer on Repeat", "authoredTitle": true, "recipient": "", "subtitle": "欧美流行", "category": "欧美流行", "filters": ["pop", "global-pop"], "tags": ["欧美流行"], "style": "欧美流行", "occasion": "", "cover": "/media/recommendations/summer-on-repeat-d8a226ab9537-cover.jpg", "audio": "/media/recommendations/summer-on-repeat-68e4b531c543-preview-128.mp3", "durationSeconds": 171.16, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nOh yeah\nSummer on repeat\nOne more time\n[Verse 1]\nWindows down on the highway\nYour sunglasses on my face\nWe got nowhere we gotta be\nJust you and me and the summer breeze\nRadio playing our favorite song\nSinging every word with the windows on\nThe sun goes down but we don’t care\nThere’s magic in the midnight air\n[Pre-Chorus]\nDon’t wanna go home\nDon’t wanna slow down\nLet’s keep this feeling\nUntil the sun comes around\n[Chorus]\nPut the summer on repeat\nDancing barefoot in the street\nEvery little memory\nFeels like a movie scene\nWe don’t ever need to sleep\nGot the whole world underneath\nYou and I, just you and me\nPut the summer on repeat\n[Post-Chorus]\nOh-oh-oh, on repeat\nOh-oh-oh, you and me\nOh-oh-oh, on repeat\nPut the summer on repeat\n[Verse 2]\nLate night drives and ocean views\nEvery little road leads back to you\nWe got a thousand miles to go\nBut I don’t wanna take it slow\n[Bridge]\nIf the summer disappears\nI’ll remember you were here\nEven when the seasons change\nWe’ll be dancing just the same\n[Final Chorus]\nPut the summer on repeat\nDancing barefoot in the street\nEvery little memory\nFeels like a movie scene\n[Outro]\nOn repeat\nYou and me\nSummer on repeat", "delivery": "CDN"},
  {"id": "golden-hour-love", "title": "GOLDEN HOUR LOVE", "authoredTitle": true, "recipient": "", "subtitle": "R&B", "category": "R&B", "filters": ["rnb"], "tags": ["R&B"], "style": "R&B节奏蓝调", "occasion": "", "cover": "/media/recommendations/golden-hour-love-2fb54a11171e-cover.jpg", "audio": "/media/recommendations/golden-hour-love-b4e20d8073b2-preview-128.mp3", "durationSeconds": 266.72, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "# GOLDEN HOUR LOVE\n\n[Intro - Soulful Humming]\nMmm, yeah\nOoh, baby\nGolden hour love\n\n[Verse 1]\nCoffee on the table\nSunlight through the blinds\nYou were reading something\nWhile I was losing track of time\n\nThere's a little music playing\nFrom the record by the door\nAnd I don't need a single thing\nThat I don't already have anymore\n\n[Pre-Chorus]\nThe world keeps turning faster\nEverybody's chasing something new\nBut I found a little heaven\nIn an afternoon with you\n\n[Chorus]\nYou're my golden hour love\nLike the sunlight from above\nWhen the whole world gets too loud\nYou're the place where I slow down\n\nYou're my golden hour love\nAnd I can't get enough\nEvery ordinary day\nFeels extraordinary when you stay\n\n[Verse 2]\nWe could drive out to the ocean\nTake the long way through the hills\nTalk about the things we're dreaming\nOr just sit and keep it still\n\nI don't need a fancy dinner\nOr a view from way up high\nJust your head upon my shoulder\nWatching colors fill the sky\n\n[Pre-Chorus]\nThe world keeps turning faster\nEverybody's chasing something new\nBut I found a little heaven\nIn an afternoon with you\n\n[Chorus]\nYou're my golden hour love\nLike the sunlight from above\nWhen the whole world gets too loud\nYou're the place where I slow down\n\nYou're my golden hour love\nAnd I can't get enough\nEvery ordinary day\nFeels extraordinary when you stay\n\n[Bridge - Soulful Vocal Runs]\nOoh, let the seasons change\nLet the years roll by\nI will find a thousand reasons\nTo keep you by my side\n\nWe don't need a perfect story\nWe don't need a perfect plan\nEvery little moment matters\nWhen you're holding my hand\n\n[Final Chorus - Rich Gospel Harmonies]\nYou're my golden hour love\nLike the sunlight from above\nWhen the whole world gets too loud\nYou're the place where I slow down\n\nYou're my golden hour love\nAnd I can't get enough\nEvery ordinary day\nFeels extraordinary when you stay\n\n[Outro - Rhodes and Vocal Ad-libs]\nGolden hour love\nOoh, golden hour love\nEvery ordinary day\nFeels extraordinary with you", "delivery": "CDN"},
  {"id": "no-reply", "title": "NO REPLY", "authoredTitle": true, "recipient": "", "subtitle": "R&B", "category": "R&B", "filters": ["rnb"], "tags": ["R&B"], "style": "R&B节奏蓝调", "occasion": "", "cover": "/media/recommendations/no-reply-549f59a34c11-cover.jpg", "audio": "/media/recommendations/no-reply-42144aef3320-preview-128.mp3", "durationSeconds": 192.36, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "# NO REPLY\n\n[Intro - Whispered Vocals]\nNo reply\nNo reply\nI see the light\nBut there's no reply\n\n[Verse 1]\nBlue light on my ceiling\nThree dots, then they disappear\nI don't know what you're feeling\nBut I know you're not here\n\nYou left without a warning\nLike a ghost inside my phone\nI've been up until the morning\nTalking to a dial tone\n\n[Pre-Chorus]\nI don't wanna chase you\nI don't wanna wait\nBut every time the screen lights up\nI still look for your name\n\n[Chorus]\nNo reply, no reply\nYou keep leaving me online\nI can see you on the other side\nBut you never tell me why\n\nNo reply, no reply\nEvery message stays behind\nI keep trying to read between the lines\nBut there's nothing left to find\n\n[Post-Chorus - Processed Vocals]\nNo-no-no reply\nNo-no-no reply\nI keep looking for a sign\nNo-no-no reply\n\n[Verse 2]\nI deleted all our pictures\nBut they're living in my head\nEvery little conversation\nEvery word we never said\n\nGot a hundred notifications\nBut none of them are you\nFunny how a crowded city\nCan feel like an empty room\n\n[Pre-Chorus]\nI don't wanna chase you\nI don't wanna wait\nBut every time the screen lights up\nI still look for your name\n\n[Chorus]\nNo reply, no reply\nYou keep leaving me online\nI can see you on the other side\nBut you never tell me why\n\nNo reply, no reply\nEvery message stays behind\nI keep trying to read between the lines\nBut there's nothing left to find\n\n[Bridge - Minimal Instrumentation]\nMaybe silence is the answer\nMaybe I should let it be\nMaybe all the things I'm asking\nAren't the things you wanna give\n\nAnd I know the night is ending\nWhen the sky turns into blue\nSo I'm putting down my phone\nAnd I'm letting go of you\n\n[Final Chorus - Layered Vocals]\nNo reply, no reply\nYou keep leaving me online\nI can see you on the other side\nBut you never tell me why\n\nNo reply, no reply\nEvery message stays behind\nI keep trying to read between the lines\nBut there's nothing left to find\n\n[Outro - Glitchy Vocal Chops]\nNo-no-no reply\nNo-no-no reply\nNothing left to find", "delivery": "CDN"},
  {"id": "too-close", "title": "TOO CLOSE", "authoredTitle": true, "recipient": "", "subtitle": "R&B", "category": "R&B", "filters": ["rnb"], "tags": ["R&B"], "style": "R&B节奏蓝调", "occasion": "", "cover": "/media/recommendations/too-close-f8d10981dcc0-cover.jpg", "audio": "/media/recommendations/too-close-6e798ee02c65-preview-128.mp3", "durationSeconds": 192.12, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "# TOO CLOSE\n\n[Intro - Soft Falsetto]\nOoh, yeah\nA little too close\nToo close to you\n\n[Verse 1]\nYou left your perfume on my sweater\nSaid we shouldn't be together\nBut you're calling after midnight\nAsking if I'm doing alright\n\nI can hear it in your breathing\nThere's a reason you're not leaving\nEvery time we say it's over\nWe just end up getting closer\n\n[Pre-Chorus]\nAnd we both know where this goes\nBut neither one of us says no\nYou put your hands around my waist\nAnd all the words just fade away\n\n[Chorus]\nWe're getting too close\nToo close to let go\nEvery time you touch me\nI forget what I know\n\nWe're getting too close\nBut I don't wanna go\nIf this is a mistake\nThen take it nice and slow\n\n[Post-Chorus - Falsetto Harmonies]\nOoh, too close\nOoh, don't let go\nOoh, too close\nTake it nice and slow\n\n[Verse 2]\nThere's a shadow on the ceiling\nAnd a thousand little feelings\nWe don't talk about tomorrow\nLike there's nothing left to follow\n\nYou keep tracing little circles\nOn my skin beneath the covers\nI could tell you that I love you\nBut you know it like no other\n\n[Pre-Chorus]\nAnd we both know where this goes\nBut neither one of us says no\nYou put your hands around my waist\nAnd all the words just fade away\n\n[Chorus]\nWe're getting too close\nToo close to let go\nEvery time you touch me\nI forget what I know\n\nWe're getting too close\nBut I don't wanna go\nIf this is a mistake\nThen take it nice and slow\n\n[Bridge - Emotional Vocal Runs]\nMaybe we don't need forever\nMaybe we just need tonight\nWe can leave the questions waiting\nTill the morning brings the light\n\nAnd if you tell me that you're leaving\nI won't ask you to explain\nBut until the sun comes rising\nCan we stay here in this place?\n\n[Final Chorus - Layered Harmonies]\nWe're getting too close\nToo close to let go\nEvery time you touch me\nI forget what I know\n\nWe're getting too close\nBut I don't wanna go\nIf this is a mistake\nThen take it nice and slow\n\n[Outro - Soft Falsetto]\nOoh, too close\nOoh, don't let go\nToo close to you", "delivery": "CDN"},
  {"id": "big-motion", "title": "BIG MOTION", "authoredTitle": true, "recipient": "", "subtitle": "Hip-Hop", "category": "Hip-Hop", "filters": ["hiphop"], "tags": ["Hip-Hop"], "style": "说唱嘻哈", "occasion": "", "cover": "/media/recommendations/big-motion-42ddce81c4a2-cover.jpg", "audio": "/media/recommendations/big-motion-d6449a11e9c6-preview-128.mp3", "durationSeconds": 144.76, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "[Intro]\nYeah, yeah\nBig motion\nAyy, look\nLet's go\n\n[Chorus]\nBig motion, I been on the move (Move)\nNew money, got nothing to prove (Nah)\nBig plays, I ain't following rules (Rules)\nWhole team eating, we ain't gonna lose (Yeah)\n\nBig motion, watch the way I step (Step)\nBig checks, I ain't finished yet (Nah)\nToo much sauce, got 'em all upset (Woo)\nCame from nothing, now they show respect (Let's go)\n\n[Verse 1]\nWalk in the room, got the whole place watching\nPhone keep ringing, got too many options\nCame with a plan, now the whole thing popping\nThey talk big, but the numbers ain't talking\n\nBack in the day, I was down on my luck\nNow I pull up and they know what's up\nNever had much, had to build my trust\nPut in that work, now the whole team up\n\nI been running through the city with a different pace\nEverybody wanna talk, but they never chase\nI don't need another friend with a friendly face\nI just need another win and a bigger place\n\nNew chain shining when the lights go low\nToo many miles on a long road home\nI was out here working when the world moved slow\nNow they all wanna know how the whole thing goes\n\n[Pre-Chorus]\nBig steps, big dreams\nWhole squad, same team\nNo sleep, all week\nNow we living what we dreamed\n\n[Chorus]\nBig motion, I been on the move (Move)\nNew money, got nothing to prove (Nah)\nBig plays, I ain't following rules (Rules)\nWhole team eating, we ain't gonna lose (Yeah)\n\nBig motion, watch the way I step (Step)\nBig checks, I ain't finished yet (Nah)\nToo much sauce, got 'em all upset (Woo)\nCame from nothing, now they show respect (Let's go)\n\n[Verse 2]\nI don't do talking, I let it unfold\nToo many stories that never got told\nStarted with nothing but dreams and a phone\nNow every city got somebody I know\n\nRunning my race, I ain't checking the time\nThey looking back while I'm crossing the line\nHad to go quiet and work on the grind\nNow every move looking perfectly timed\n\nBig plans, had to make 'em come true\nNew wave, had to make it brand new\nThey see the shine, never saw what I knew\nI had to bet on myself and come through\n\n[Bridge]\nAyy, ayy\nBig motion, no slowing\nWhole team, we going\nBig motion, keep rolling\n\n[Final Chorus]\nBig motion, I been on the move (Move)\nNew money, got nothing to prove (Nah)\nBig plays, I ain't following rules (Rules)\nWhole team eating, we ain't gonna lose (Yeah)\n\nBig motion, watch the way I step (Step)\nBig checks, I ain't finished yet (Nah)\nToo much sauce, got 'em all upset (Woo)\nCame from nothing, now they show respect (Let's go)\n\n[Outro]\nBig motion\nBig motion\nYeah, we moving", "delivery": "CDN"},
  {"id": "running-through-my-mind", "title": "RUNNING THROUGH MY MIND", "authoredTitle": true, "recipient": "", "subtitle": "Hip-Hop", "category": "Hip-Hop", "filters": ["hiphop"], "tags": ["Hip-Hop"], "style": "说唱嘻哈", "occasion": "", "cover": "/media/recommendations/running-through-my-mind-929b4cf02f9a-cover.jpg", "audio": "/media/recommendations/running-through-my-mind-226bea4d3e2d-preview-128.mp3", "durationSeconds": 174.0, "provenance": "SAMPLE", "description": "", "quote": "", "lyrics": "# RUNNING THROUGH MY MIND\n\n[Intro]\nYeah\nOh-oh\nYou're still running through my mind\nYeah, yeah\n\n[Chorus - Melodic Rap]\nYou're running through my mind again\nI don't wanna fall again\nEvery time I close my eyes\nI see you on the other side\n\nI've been driving through the rain again\nTrying not to call again\nEvery time I say goodbye\nYou're still running through my mind\n\n[Verse 1 - Rhythmic Rap]\nTwo in the morning, I'm up on the road\nGot a lot on my chest, but I keep it on low\nYou were the one that I wanted the most\nNow I'm talking to shadows and chasing a ghost\n\nI been moving too fast, gotta slow my pace\nEvery city I visit got a familiar face\nEvery song on the radio brings your name\nAnd I'm tired of pretending that it don't feel strange\n\nI put my phone on silent, but I still check twice\nI got a new apartment, but it don't feel right\nAll these people around, but I'm alone at night\nI could buy what I want, can't buy back time\n\nI was chasing a dream, you were waiting at home\nNow I'm living that dream, but I'm sleeping alone\nFunny how everything that I wanted came true\nExcept the one thing I was building it for was you\n\n[Pre-Chorus - Melodic]\nAnd I know, I know\nWe can't go back anymore\nBut I still, I still\nKeep looking for you at the door\n\n[Chorus - Melodic Rap]\nYou're running through my mind again\nI don't wanna fall again\nEvery time I close my eyes\nI see you on the other side\n\nI've been driving through the rain again\nTrying not to call again\nEvery time I say goodbye\nYou're still running through my mind\n\n[Verse 2 - Fast Melodic Rap]\nI got a couple new friends and a couple new places\nBut I still see you in a thousand faces\nTried moving on, tried changing the station\nBut I keep going back to the same conversation\n\nI know I made mistakes, I know I was wrong\nI know you got tired of waiting so long\nI know you don't wanna hear another sad song\nBut I wrote one anyway, couldn't hold it for long\n\nNow the rain on the glass and the lights on the street\nGot me thinking 'bout the nights when you were next to me\nI could say I'm doing fine, but it's hard to believe\nWhen the only thing I want is the thing I had to leave\n\n[Bridge - Half-Sung]\nIf I could turn the time around\nWould you still be here somehow?\nAll these miles and all these lights\nCan't get you off my mind tonight\n\n[Final Chorus]\nYou're running through my mind again\nI don't wanna fall again\nEvery time I close my eyes\nI see you on the other side\n\nI've been driving through the rain again\nTrying not to call again\nEvery time I say goodbye\nYou're still running through my mind\n\n[Outro]\nRunning through my mind\nRunning through my mind\nYou're still running through my mind", "delivery": "CDN"},
]

// Only these built-in demos are translated. User-created music keeps its authored title.
export function usePreviewTracks() {
  return computed(() => tracks.map(track => ({ ...track, titleKey: track.authoredTitle?undefined:track.title,
    title: track.authoredTitle?track.title:t(track.title), recipient: t(track.recipient), subtitle: t(track.subtitle),tags:track.tags.map(tag=>t(tag)),
    style: t(track.style), description: t(track.description), quote: t(track.quote), category: t(track.category),genre:t(track.genre||track.category),
  })))
}
