# 广场横幅素材

`editorial-coast.webp` 用于广场介绍首屏。后续五张热门作品轮播分别使用下面五张独立背景，按热门榜顺序配图；歌名和作者读取业务接口，用页面文字显示，不写死在背景图片里。素材不代表某一首实际作品。

使用内置 imagegen 生成，原图 2172 × 724，WebP 质量 88。提示词：全景海边公路，自然的蓝色海面和午后阳光，一对青年从背后走在画面右侧，左侧保留海天留白；自然摄影，无文字、标志或水印。网页标题及按钮由代码渲染。

后五张来自用户提供的「轮播图封面」照片，按文件名升序排列。照片经 imagegen 横向扩展到 2172 × 724，转为 WebP（质量 88）。页面按每张图的主体位置裁切，并预取下一张背景。

| 轮播位置 | 原始照片 | 横幅文件 |
| --- | --- | --- |
| 第 2 张 | `IMG_2919.JPG` | `carousel-cactus.webp` |
| 第 3 张 | `IMG_2920.JPG` | `carousel-misty-garden.webp` |
| 第 4 张 | `IMG_2921.JPG` | `carousel-airport-guitar.webp` |
| 第 5 张 | `IMG_2922.JPG` | `carousel-sea-stacks.webp` |
| 第 6 张 | `IMG_2923.JPG` | `carousel-countryside.webp` |

## 行书字体

歌曲轮播标题使用魏志莽的「志莽行书」（Zhi Mang Xing，Version 2.001）。由 Google Fonts 的完整 TTF 转成 WOFF2，保留全部 7015 个 Unicode 字符映射，不按当前歌名裁剪；字体随项目托管，`font-display: swap`，加载期间可使用系统字体。

- 字体：`fonts/ZhiMangXing-Regular.woff2`
- 来源：[Google Fonts / Zhi Mang Xing](https://github.com/google/fonts/tree/main/ofl/zhimangxing)
- 许可：[SIL Open Font License 1.1](https://raw.githubusercontent.com/google/fonts/main/ofl/zhimangxing/OFL.txt)，完整版权与许可随 `fonts/OFL.txt` 一同保留。
- 原始 TTF SHA-256：`644e0cae9b40f0b10ab729a01bd32032e3973bac22be3dccae01bf6ae7fde969`
- WOFF2 SHA-256：`900fa771d3824e82e730345ca3d456bdaf8aa7c768b82eb9fe38fb8d7ed338c4`
