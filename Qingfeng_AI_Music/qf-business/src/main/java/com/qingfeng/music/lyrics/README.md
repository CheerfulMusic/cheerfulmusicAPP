# lyrics

歌词生成、版本与确认。

已实现LyricsApi、手动歌词新版本、历史读取与确认，以及DeepSeek同步编写。版本绑定作品版本，不覆盖旧正文。通过WorkApi核验归属与作品版本，LyricsRepository由infrastructure适配；不引用其他模块Mapper。

CompositionRequests保存请求幂等标识、结果版本和错误码。失败请求释放并发但保留每日额度；过期PENDING可恢复，迟到结果通过事务内状态更新拦截。状态读取核验本人归属，页面只在用户明确重新编写时提交新请求，不自动重试付费模型。详见根目录docs/歌词请求异常恢复.md。
