# KIE Suno 接入

2026-11-02：已完成代码接入，用户随后明确要求开启歌曲生成，实际local.properties中已启用。只读验证认证成功，账户曾返回80积分；未代用户提交付费任务或上传测试歌词。点击网页“生成歌曲”才会创建任务。真实模型权限、歌曲响应结构与实际CDN归档仍待实际生成确认。

## 接口与流程

- [生成接口文档](https://docs.kie.ai/suno-api/generate-music)：POST `/api/v1/jobs/createTask`，外层模型为`ai-music-api/generate`，input模型默认`V6`。自定义模式将已确认歌词原文作为`lyrics`提交，`instrumental=false`请求人声歌曲。
- [查询接口文档](https://docs.kie.ai/market/common/get-task-detail)：GET `/api/v1/jobs/recordInfo?taskId=...`。本地使用轮询，不要求公网回调地址。
- [积分查询](https://docs.kie.ai/common-api/get-account-credits)：GET `/api/v1/chat/credit`，仅用于本次只读认证验证，不暴露在公开网页。

现有流程为：保存故事 → 手动填写及确认歌词 → 创建持久化生成任务 → worker提交一次 → 查询进度 → 下载并归档音频 → 现有播放器、下载及礼物分享。尚未接入AI自动写词。平台可能在一次请求中生成多个版本；当前业务端口仅归档返回的首个音频，其他变体不逐一入库。

KieSunoMusicProvider位于qf-infrastructure，ProviderConfiguration负责装配，业务继续使用MusicProvider端口。任务表、幂等请求键、租约和归档重试沿用原实现，无新数据库迁移。

## 本地配置

实际密钥已写入后端`config/local.properties`，不在前端或示例配置中。也支持`KIE_API_KEY`环境变量（使用时应移除本地同名密钥设置，以免覆盖）。当前实际设置：

```properties
qingfeng.generation.provider=kie-suno
qingfeng.providers.kie.model=V6
qingfeng.generation.enabled=true
```

当前使用已认证的KIE查询响应登记精确音频URL，无需提前猜测CDN域名。登记不向浏览器开放，URL须为HTTPS；下载仍检查内部地址、端口、凭据及每次跳转。进程重启后，若SAVING任务首次归档未命中内存登记，原有归档恢复流程会只读查询供应商、重新登记地址并再次归档，不重新生成歌曲。

如有已知固定CDN，也可额外配置精确域名：

```properties
# 可选固定域名，以逗号分隔；不填写时使用KIE认证查询返回的精确URL。
qingfeng.generation.audio-hosts=
# 实际私有配置已开启；修改后需要重新运行IDEA后端。
qingfeng.generation.enabled=true
```

下载器拒绝未登记且不在固定域名列表内的URL，同域名其他路径或不同签名参数不会因登记一个URL而自动获准。存储沿用原本local／OSS设置。项目默认配置仍为enabled=false；此次仅对本机私有配置开启。

## 错误与验证

标题最多80字、歌词最多5000字、风格最多1000字，提交前检查且不静默截断歌词。明确的认证、余额及参数拒绝归为失败；断连、5xx或缺少taskId视为结果未知，worker不自动重新提交。查询及音频归档可重试，不重复购买歌曲。供应商原始响应、密钥不返回给浏览器。

后端39项测试通过（含新增5项本地HTTP协议测试）；前端12项测试通过。新增测试覆盖原文歌词提交、状态与音频结果解析、过长输入、明确拒绝与未知结果、任务编号不匹配。前端创作提示根据真实能力显示，中英文均已补齐。模拟接口测试不代表真实Suno歌曲生成已通过。
