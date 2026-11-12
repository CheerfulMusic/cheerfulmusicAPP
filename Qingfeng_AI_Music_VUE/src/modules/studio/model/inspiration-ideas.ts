type InspirationIdea = readonly [labelZh: string, labelEn: string, storyZh: string, storyEn: string]

export const inspirationGroups = [
  [
    ['治愈系', 'A little comfort', '今天终于可以松口气——把白天没说出口的「我累了」唱给自己。', 'I can finally breathe. Write a song to myself about the “I’m tired” I never said aloud today.'],
    ['关于生活', 'Everyday life', '晚饭后的碗还泡在水里，窗外有人遛狗。想把这样普通又安稳的一晚写成歌。', 'The dinner dishes are still soaking and someone is walking their dog outside. Write a song about an ordinary, peaceful evening.'],
    ['想写给自己', 'To myself', '给那个总说「没关系」的自己写首歌，记得下次也问问自己想要什么。', 'Write to the person who always says “it’s fine.” Next time, remember to ask yourself what you want.'],
    ['城市夜色', 'City at night', '雨停了，末班车经过一盏盏路灯。写一首在热闹城市里慢慢安静下来的歌。', 'The rain has stopped and the last bus passes one streetlight after another. Write about finding quiet in a busy city.'],
  ],
  [
    ['写给家人', 'For family', '回家晚了，厨房里还有一碗热汤。想把家人平时没说出口的关心写进歌里。', 'I came home late and found a bowl of warm soup waiting. Put the care my family rarely says aloud into a song.'],
    ['老友重逢', 'Old friends', '很久没见的人突然发来一张合照。第一句还是当年那个只有我们懂的玩笑。', 'An old friend sent a photo out of nowhere. The first thing we said was the joke only we used to understand.'],
    ['谢谢陪伴', 'Thanks for being there', '想谢谢那个陪我等末班地铁的人。我们没聊什么，但那晚我没有一个人回家。', 'Thank the person who waited with me for the last train. We barely talked, but I didn’t go home alone that night.'],
    ['想说抱歉', 'An apology', '那天走得太急，没听完你最后一句话。想用一首歌把迟到的抱歉说清楚。', 'I left too quickly to hear the last thing you said. Write a song that finally says the apology I owe you.'],
  ],
  [
    ['第一次看海', 'First glimpse of the sea', '还没走到沙滩，风里已经有了咸味。写下我第一次站在海边时说不出话的感觉。', 'I could taste salt before I reached the sand. Write about being speechless the first time I saw the sea.'],
    ['公路旅行', 'Road trip', '车窗开了一条缝，地图上的小镇一个个往后退。想写一首可以边开车边唱的歌。', 'The window is cracked open and the towns on the map fall behind us. Write something we can sing on the road.'],
    ['山里清晨', 'Mountain morning', '清晨起雾，屋檐下有人把水烧开。用一首歌留住山里醒得很慢的那一天。', 'Mist gathers at dawn while someone boils water under the eaves. Keep a morning that woke slowly in the mountains.'],
    ['雨天车窗', 'Rain on the window', '雨珠沿车窗往后跑，我看着熟悉的街口变模糊。想写一首关于舍不得离开的歌。', 'Rain runs backward across the window and a familiar street blurs. Write about being reluctant to leave.'],
  ],
  [
    ['生日祝福', 'Birthday wish', '蜡烛吹灭前大家忽然安静了。想把我希望你过得自在一点的心愿写进生日歌。', 'Everyone went quiet just before the candles went out. Write a birthday song wishing you a little more room to be yourself.'],
    ['毕业季', 'Graduation', '把钥匙交回去后，最后一次经过教室。想给那几年写一首轻快的告别歌。', 'After handing back the key, I passed the classroom one last time. Write a lighthearted goodbye to those years.'],
    ['新工作', 'First day at work', '第一天上班，提前半小时到了楼下，却不敢马上进去。写写紧张和期待并存的早晨。', 'I arrived half an hour early on my first day and hesitated outside. Write about a morning of nerves and excitement.'],
    ['搬家日', 'Moving day', '纸箱都封好了，只剩钥匙还在桌上。想写一首从旧地址走向新生活的歌。', 'The boxes are sealed and only the keys remain on the table. Write about leaving an old address for a new life.'],
  ],
  [
    ['悄悄喜欢', 'A quiet crush', '为了和你多走一段路，我假装没看见自己的站牌。写一首把喜欢藏在小动作里的歌。', 'I pretended not to see my stop so I could walk a little farther with you. Write about a crush hidden in small gestures.'],
    ['认真告白', 'Saying it out loud', '这句话在心里练了很多遍，见到你还是想笑。想把喜欢说得简单一点。', 'I rehearsed it in my head, but seeing you still makes me smile. Write a song that says I like you simply.'],
    ['异地想念', 'Miles apart', '我这边天刚亮，你那边准备睡了。想把两个时区里对同一个人的想念唱出来。', 'The sun is rising here as you get ready for bed. Write about missing the same person across two time zones.'],
    ['我们的纪念日', 'Our anniversary', '又去了第一回见面的那家店，菜单都换了，我们还是点了同一杯饮料。', 'We went back to the place we first met. The menu had changed, but we still ordered the same drink.'],
  ],
  [
    ['重新出发', 'Starting over', '停了很久的鞋终于又摆在门口。写一首关于重新迈出第一步的歌。', 'The shoes I haven’t worn in months are finally by the door. Write about taking the first step again.'],
    ['告别旧日', 'Letting go', '把那件一直没舍得扔的旧衣服叠好，放进捐赠箱。想写一首安静的告别歌。', 'I folded the old shirt I couldn’t part with and put it in a donation box. Write a quiet goodbye.'],
    ['小小胜利', 'A small win', '终于把拖了很久的一件事做完，回家给自己买了一个蛋挞。写首歌庆祝这点进步。', 'I finished something I’d put off for ages and bought myself a custard tart on the way home. Celebrate a small win.'],
    ['梦想未完', 'A dream on hold', '翻出以前写满计划的笔记本，发现有一页还想接着写。想给那件没做完的事写首歌。', 'I found an old notebook full of plans and one page I still want to finish. Write about a dream on hold.'],
  ],
  [
    ['晚风夜跑', 'A night run', '跑过河边时风有点凉，耳机里刚好换了一首歌。想写一首跟脚步一起加速的歌。', 'The river breeze cooled as the next song started in my headphones. Write something that picks up pace with my steps.'],
    ['清晨咖啡', 'Morning coffee', '咖啡机开始响，第一缕阳光照到杯沿。想写一首适合慢慢开启一天的歌。', 'The coffee machine hums as the first light reaches the cup. Write a song for easing into the morning.'],
    ['周末厨房', 'Weekend kitchen', '有人切菜，有人哼歌，锅盖被蒸汽顶得轻响。把周末厨房里的声音写成歌。', 'Someone chops vegetables, someone hums, and the pot lid rattles softly. Turn the sounds of a weekend kitchen into a song.'],
    ['回家路上', 'On the way home', '走到熟悉的路口就闻到了烤红薯的味道。想把归途写得温暖一些。', 'I smelled roasted sweet potatoes at the familiar corner. Write a warm song about the way home.'],
  ],
  [
    ['童年夏天', 'Childhood summer', '风扇吹着湿头发，冰箱里还剩半个西瓜。想写一首关于童年夏天下午的歌。', 'The fan dries my hair and half a watermelon is still in the fridge. Write about a summer afternoon from childhood.'],
    ['一件老物件', 'An old keepsake', '抽屉里找到一块早就不走的表，表带上还留着磨旧的痕迹。想写写我舍不得丢它的理由。', 'I found a watch that stopped years ago, its strap worn smooth. Write about why I still keep it.'],
    ['季节更替', 'Changing seasons', '出门第一次需要加一件外套，路边的树也悄悄变了颜色。写写日子是怎么往前走的。', 'For the first time I needed another layer outside, and the trees had quietly changed. Write about the way time moves on.'],
    ['胶片回忆', 'On film', '洗出来的照片有一张拍糊了，却最像我记得的那天。想写一首关于模糊回忆的歌。', 'One of the developed photos is blurry, yet it looks most like the day I remember. Write about a memory out of focus.'],
  ],
  [
    ['一个人住', 'Living alone', '第一次只给自己煮一碗面，吃完慢慢把灯关了。想写独处里渐渐长出的踏实。', 'I cooked one bowl of noodles for myself and turned off the lights slowly. Write about the steadiness that comes with living alone.'],
    ['和自己和解', 'Making peace with myself', '整理旧消息时发现，那件事已经没那么疼了。想写一首慢慢放过自己的歌。', 'Going through old messages, I realized it doesn’t hurt as much anymore. Write about slowly learning to forgive myself.'],
    ['给未来的我', 'Dear future me', '把现在舍不得放下的一件事写进歌里，想问三年后的自己，它还那么重要吗？', 'Put one thing I can’t let go of into a song and ask my future self if it will still matter in three years.'],
    ['平凡的一天', 'An ordinary day', '没发生大事，但路上有人替我扶了一下门。想写一首关于这些小好事的歌。', 'Nothing big happened, but someone held the door for me. Write a song about small kindnesses.'],
  ],
  [
    ['一起看星星', 'Under the stars', '大家一起关掉手电，才发现头顶的星星有那么多。写一首关于安静待在一起的歌。', 'We turned off our flashlights and saw how many stars were overhead. Write about being quietly together.'],
    ['街角书店', 'Corner bookshop', '书店快打烊了，我在旧书里翻到一张陌生人留下的车票。想沿着这张票编一段故事。', 'The bookshop was closing when I found a stranger’s ticket in a used book. Imagine where the ticket might lead.'],
    ['跨年烟火', 'New Year fireworks', '人群数到最后一秒，烟火亮起来，我却在想今年哪件小事最值得记住。', 'The crowd counted down and the fireworks lit up. I wondered which small moment from this year I most wanted to keep.'],
    ['海边落日', 'Sunset by the sea', '最后一点光落进海里，鞋里都是沙。想写一首关于舍不得结束的一天的歌。', 'The last light falls into the sea and my shoes are full of sand. Write about a day I don’t want to end.'],
  ],
] as const satisfies readonly (readonly InspirationIdea[])[]
