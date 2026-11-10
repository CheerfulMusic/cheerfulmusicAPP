首页试听使用 `public/media` 中的九首完整音频，来源为用户提供的桌面文件。曲目、主题、标签、筛选和真实时长统一在 `model/tracks.ts` 维护，页面与标签支持中英双语，用户歌曲保留原歌名。

| 歌曲 | 风格依据 | 音频文件 |
| --- | --- | --- |
| 把平凡唱给你 | 原爱情主题范例 | sample-love-v2.mp3 |
| 走了那么远 | 文件名标注流行，同时保留原家庭分类 | sample-family-v2.mp3 |
| 去有海风的地方 | 原自由旅行范例 | sample-ocean-travel-v2.mp3 |
| 红 | 文件名标注古风 | sample-red-guofeng-v1.mp3 |
| 忽然下起雨 | 文件名标注 Disco | sample-rain-disco-v1.mp3 |
| 明天再说 | 文件名标注 rock | sample-tomorrow-rock-v1.mp3 |
| 最美的风景 | 文件名标注民谣 | sample-scenery-folk-v1.mp3 |
| hometown lover | 文件名标注电子 | sample-hometown-electronic-v1.mp3 |
| When the River Breathes | 文件名标注纯音乐 | sample-river-instrumental-v1.mp3 |

桌面“家庭范例歌.mp3”和“走了那么远（流行）.mp3”经 SHA-256 核对内容完全相同，复用同一个音频并只展示一张卡片，可通过家庭、流行两个标签筛选。新增六个 MP3 保留原始字节；原爱情 WAV 已转换为 MP3，未重新编码。

Disco、摇滚、电子、纯音乐卡片直接提取原音频的内嵌封面，古风使用本地 SVG 插画，民谣复用吉他主题图。标签与文案按文件名做展示分类，不冒充实际歌词或专业听音结论。纯音乐卡片的创作链接使用现有“空灵氛围”建议，不改变音乐生成接口的演唱模式。

全站能力查询位于 `shared/http/system.ts`。这些音频属于公开试听静态资源，随前端构建进入 `dist/media`，不依赖桌面路径或浏览器缓存。

生产构建现在通过 Vite 的 `verify-preview-media` 插件检查曲目表中每个 audio/cover 引用，文件缺失、为空或 MP3 头无效时立即失败。构建输出 `preview-media-manifest.json`，记录文件长度与 SHA256；发布制品检查还会核对 JAR 中每个文件及编译后音频引用。稀疏检出时必须显式保留 `public/media` 的九首 MP3，不能以构建命令成功代替实际媒体文件检查。

首页采用三个紧凑歌单：听见心意、自由与远方、跟着节奏出发。分组顺序在 `model/playlists.ts` 中维护，卡片最多预览三首；“查看更多”打开歌单详情，可试听、播放歌单和选择单曲风格创作。新增歌曲须同时加入对应歌单，不能只修改曲目表。

默认只显示全部、爱情、家庭、自由旅行筛选，七种音乐风格收在“更多风格”中；选择风格后只展示对应歌曲，歌单详情仍保留该歌单的完整内容。手机端可横向滑动歌单，不把所有歌单堆成纵向长页面。
