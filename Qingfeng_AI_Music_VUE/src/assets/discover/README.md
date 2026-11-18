# 广场横幅素材

`editorial-coast.webp` 用于广场介绍首屏。后续五张热门作品轮播分别使用下面五张独立背景，按热门榜顺序配图；歌名和作者读取业务接口，用页面文字显示，不写死在背景图片里。素材不代表某一首实际作品。

使用内置 imagegen 生成，原图 2172 × 724，WebP 质量 88。提示词：全景海边公路，自然的蓝色海面和午后阳光，一对青年从背后走在画面右侧，左侧保留海天留白；自然摄影，无文字、标志或水印。网页标题及按钮由代码渲染。

| 文件 | 设计 | 歌名区 |
| --- | --- | --- |
| `carousel-neon.webp` | 雨夜霓虹：蓝紫城市、雨幕、青红反光 | 深蓝色中央留白 |
| `carousel-meadow.webp` | 晨光旷野：清晨草地、野花、暖金晨雾 | 柔和绿色中央留白 |
| `carousel-mountains.webp` | 东方山水：青灰山峦、水墨烟岚、铜色日轮 | 中央淡雾 |
| `carousel-moon.webp` | 紫月云海：紫色夜空、玫瑰色云层、弯月 | 暗紫色中央留白 |
| `carousel-stage.webp` | 金色舞台：暖金光束、钢琴、复古话筒、深色帷幕 | 深色中央留白 |

## 图片来源与复现提示词

2026-11-18 使用 Codex 内置 imagegen 工具新生成五张背景。原图均为 2172 × 724，转为 WebP（quality 88）；无文字、无 Logo、无水印。图片随项目打包，不依赖外部图片链接。页面只预取下一张轮播背景。

共同提示词：`Create one premium music website carousel background, seamless panoramic 3:1 banner, approximately 2172 x 724. Cinematic editorial composition. Place the characteristic scenery at the outer thirds and reserve a spacious quiet central title zone for a white handwritten song title rendered later by webpage code. No text, lettering, logos, watermark, frame, or UI.`

分别追加的设计提示词：

- 雨夜霓虹：`A rainy nocturnal city with restrained cyan and magenta neon reflections, atmospheric wet streets, cinematic photography, rich deep blue central negative space. No readable signs or people in the central title area.`
- 晨光旷野：`A peaceful sunrise meadow, warm golden light, delicate wildflowers near the edges, layers of mist and soft green rolling fields. Calm, hopeful, airy editorial photography with a quiet central title area.`
- 东方山水：`Elegant Chinese ink landscape, blue-gray mountain silhouettes framing both sides, layered mist, delicate ink wash texture, a restrained copper sun toward the upper right. Spacious pale atmospheric center, sophisticated modern album art.`
- 紫月云海：`Dreamlike violet night sky above a rose-tinted sea of clouds, a delicate crescent toward the left, subtle stars, expansive deep purple central sky, poetic and serene, refined cinematic album art.`
- 金色舞台：`An intimate elegant music stage in deep chocolate black and warm amber tones. A vintage grand piano only in the far right outer third, a slim microphone stand at the far left edge, velvet curtains, warm side spotlights and subtle floating dust. Keep the central half dark and spacious. No performers or faces.`

## 行书字体

歌曲轮播标题使用魏志莽的「志莽行书」（Zhi Mang Xing，Version 2.001）。由 Google Fonts 的完整 TTF 转成 WOFF2，保留全部 7015 个 Unicode 字符映射，不按当前歌名裁剪；字体随项目托管，`font-display: swap`，加载期间可使用系统字体。

- 字体：`fonts/ZhiMangXing-Regular.woff2`
- 来源：[Google Fonts / Zhi Mang Xing](https://github.com/google/fonts/tree/main/ofl/zhimangxing)
- 许可：[SIL Open Font License 1.1](https://raw.githubusercontent.com/google/fonts/main/ofl/zhimangxing/OFL.txt)，完整版权与许可随 `fonts/OFL.txt` 一同保留。
- 原始 TTF SHA-256：`644e0cae9b40f0b10ab729a01bd32032e3973bac22be3dccae01bf6ae7fde969`
- WOFF2 SHA-256：`900fa771d3824e82e730345ca3d456bdaf8aa7c768b82eb9fe38fb8d7ed338c4`
