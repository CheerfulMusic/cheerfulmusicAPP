# 播放歌词

`PlaybackLyricsService` 负责本人音频的歌词读取、显式同步与缓存复用。通过 `MediaApi` 验证音频归属，依赖 `PlaybackLyricsRepository` 和 `LyricsTimingProvider` 两个端口，不依赖 KIE 或数据库实现。

时间轴跟随不可变的归档音频与歌曲生成快照，与歌词编辑历史分开。`PlaybackLyrics.lines` 只依据有效供应商时间分组，不估算整首歌的逐句时长。基础设施适配器在短事务内登记同步与预留 TIMING 额度，然后在事务外调用时间轴服务。未知结果保留，禁止自动重发。
