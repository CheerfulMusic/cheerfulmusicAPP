# DeepSeek 歌词编写

2026-11-02，DeepSeek密钥已保存到后端私有config/local.properties；只读认证、模型列表及余额检查通过，可用deepseek-flash与deepseek-v4-pro，当前配置选择deepseek-flash，关闭思考模式以直接输出歌词。仅在用户点击“根据故事生成歌词”时调用，不自动调用Suno。

## 使用

在IDEA重新运行MusicApplication后，先保存故事。点击创作页“继续制作音乐礼物”会保存最新修改，再请求DeepSeek，成功后进入作品详情并定位到该歌词版本。生成期间禁用表单与重复点击；失败留在原页面并保留故事。后端读取该用户作品中的姓名、场景、故事、祝福和风格，保存source=DEEPSEEK的未确认版本。

详情页中歌词可以直接修改、另存新版本并确认，再点击“生成歌曲”。已有歌词时按钮显示“重新生成歌词”，每次明确重新生成都使用新请求键，原版本和手动修改版本保留。继续流程的请求键按页签、作品版本和语言保存在sessionStorage，返回或刷新后重试同一次继续操作会复用请求键；刷新详情页只加载歌词，不触发模型。切换版本、保存或重新生成会同步页面版本参数，刷新后恢复所选版本。

语言切换只翻译界面，不改写歌词；首次继续使用当前界面语言，详情页重新生成可独立选择歌词语言。

本次已开启本机私有设置，示例配置保持关闭且不包含密钥：

```properties
qingfeng.providers.deepseek.model=deepseek-flash
qingfeng.lyrics.enabled=true
```

也支持DEEPSEEK_API_KEY环境变量，本地同名配置优先。密钥不进入前端、接口响应或提示词。

## 实现

- business：LyricsWriter模型端口、LyricsCompositionService、CompositionRequests持久化去重端口。
- infrastructure：DeepSeekLyricsWriter、JdbcCompositionRequests；固定请求官方HTTPS接口，Bearer认证，不跟随重定向，不自动重试。
- server：POST `/api/app/works/{work}/lyrics/generate`，请求workVersion、requestKey(UUID)、language(zh-CN/en)。沿用创建者会话、CSRF、作品归属和版本校验。
- Flyway V6新增lyrics_composition_request，仅记录请求状态及生成版本引用；成功请求重放返回已有版本，处理中或结果未知的请求不再次调用供应商。未保存的手动修改需先确认才能在编辑器中替换。
- 请求使用JSON输出和max_tokens=2400；只接收finish_reason=stop的完整内容。标题不超过80字、歌词不超过5000字。超时、余额不足或无效响应保留原编辑内容并显示可读错误，前端生成请求等待120秒，后端模型请求90秒。

## 验证边界

后端45项测试通过，新增覆盖HTTP请求及JSON响应、截断拒绝、作品归属、陈旧版本、重复请求不重扣费、未确认AI版本及旧歌词保留。前端14项测试及构建通过，包含继续请求在刷新后复用与版本／语言隔离。DeepSeek真实认证与余额查询通过；浏览器生成交互使用模拟结果，未发起真实DeepSeek或Suno付费生成。继续与重新生成的浏览器证据位于.cache/continue-lyrics-qa.json及.cache/screenshots/continue-*.png。

官方文档：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[模型列表](https://api-docs.deepseek.com/api/list-models/)、[余额查询](https://api-docs.deepseek.com/api/get-user-balance/)。
