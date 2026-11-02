# lyrics

歌词生成、版本与确认。

已实现LyricsApi、手动歌词新版本、历史读取与确认，版本绑定作品版本，不覆盖旧正文。AI歌词生成暂缓。通过WorkApi核验归属与作品版本，LyricsRepository由infrastructure适配；不引用其他模块Mapper。
