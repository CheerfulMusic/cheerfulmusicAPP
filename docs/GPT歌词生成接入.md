# GPT 歌词生成

## 本次范围

生成歌词、按修改要求重新生成歌词，按 `qingfeng.lyrics.provider` 选择提供方。选 `openai` 时使用官方 `https://api.openai.com/v1/responses`，默认型号 `gpt-6.1-terra`，`reasoning.effort=low`，单次输出上限 6000 tokens，请求超时 90 秒。型号可以通过服务端配置调整，需要使用支持 Responses、严格结构化输出和 low reasoning 的模型。

复用现有写词提示词、语言选择、段落模板、保留原句要求、音乐设置与 CreativePlan；不改变歌词版本保存流程。新增 GPT 版本来源记为 `OPENAI`，DeepSeek 仍记为 `DEEPSEEK`，历史数据不改写。灵感输入框的“AI 改写”、音乐生成、封面生成仍保持各自原有提供方。

## 配置

以下字段只能写进服务端私有配置，或通过 `OPENAI_API_KEY` 提供密钥。禁止放入前端环境变量、浏览器请求、仓库或文档示例。

```properties
qingfeng.lyrics.enabled=true
qingfeng.lyrics.provider=openai
qingfeng.providers.openai.model=gpt-6.1-terra
# qingfeng.providers.openai.api-key=（仅在私有配置填写）
```

2026-11-09 已从用户指定的桌面记事本读取密钥，写入 Git 忽略的 `Qingfeng_AI_Music/config/local.properties`，并设置以上提供方与型号。修改前的配置备份在本机忽略目录 `.backups/gpt-lyrics/`，没有改动数据库连接、后台调度或支付配置。

未显式配置提供方的既有部署仍走 DeepSeek，避免未配置 GPT 密钥时意外切换。手动回退只需将提供方改为 `deepseek` 并保留原 DeepSeek 密钥，再按授权流程重启。调用失败不会自动切换模型，也不会自动重发付费请求。

照片写歌入口默认关闭。在隔离的本地验证环境中，配置支持图片输入的 OpenAI 模型与私有密钥，并设置 `qingfeng.lyrics.photo.enabled=true`。普通写词仍可使用原提供方。照片入口接受单张 JPG/PNG（最大 5 MB），后台将缩放后的图和选填故事一起送入一次 Responses 请求。应用保存画面摘要、歌词和音乐方向，原图不进入作品存储。离线端到端验证可用 `PhotoLyricsIntegrationTest`，它使用 H2 和替代供应商。

日常主库启动仍必须遵循 `scripts/start-main.ps1` 约定，使用 `.secrets/production-main.local.properties`、版本清单及线上同版 JAR。当前这些主库启动材料在此工作区缺失，本次没有创建替代数据库、启动旧入口或替换运行版本。准备主库启动材料时，应将上述 GPT 配置安全地并入该私有配置；不得直接以旧 `local.properties` 作为完整主库配置。

## 错误与数据保护

- 请求采用严格 JSON Schema，返回标题、歌词、六字段音乐计划；服务端再次检查类型、必填字段和长度。
- 不将 reasoning、拒绝文本、截断内容、缺失字段或错误响应保存为歌词。
- 网络超时、中断、授权失败、限流/额度不足均返回现有歌词错误码与安全提示；不回传或记录供应商响应正文、请求素材和认证头。
- `store=false`，不创建 OpenAI 服务端可检索的响应记录；这不等于供应商零数据保留承诺。
- 本次不修改生产数据库，不调用音乐生成服务，不自动上线。

## 验证与当前限制

协议测试用本机模拟 HTTP 服务，覆盖请求素材保留、JSON Schema、CreativePlan、异常响应、拒绝、截断、无效结果、不重试及提供方配置。H2 流程验证覆盖 GPT 来源、歌词版本、音乐计划、请求重放和重新编写；现有 DeepSeek 测试继续保留。

完整 `mvn verify` 已通过：全模块 361 项通过，27 项需要显式启用独立 MySQL 测试环境的用例跳过，无失败。构建的 JAR 已检查，不含 `local.properties` 或 `.secrets` 私有配置。

2026-11-09 本机对官方 `/v1/models` 的只读查询超时，未获取到授权响应。因此尚未确认此密钥的有效性、账户可用型号和真实歌词效果，也未发起真实写词付费请求。需要在可正常访问官方 API 的受支持运行环境完成一次虚构素材冒烟验证后，才能判定真实调用可用。

参考：

- [GPT-6.1 Terra 型号与能力](https://developers.openai.com/api/docs/models/gpt-6.1-terra)
- [Responses 严格结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)
- [当前模型迁移指南](https://developers.openai.com/api/docs/guides/latest-model)
