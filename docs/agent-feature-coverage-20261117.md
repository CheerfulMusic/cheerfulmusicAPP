# Agent 现有功能覆盖核对（2026-11-17）

范围是当前系统已有的用户功能；不把计划中的商业发行、无缝音频延长、未启用的模型服务说成可用。

## 执行方式

| 功能 | 对话执行方式 |
| --- | --- |
| 灵感、歌词、单句/选段改写、押韵、保留行、版本保存与确认 | 真实业务工具；预览与保存分开 |
| 音乐生成、设置、音频局部替换、一键创作、任务恢复 | 真实业务工具，原任务与计费规则不变 |
| 音乐生成前检查 | generation_preflight：复用生成接口预检，不提交任务 |
| 歌词下载 | download_lyrics：读取指定或当前版本，提供 TXT 下载按钮 |
| 封面上传、选择、AI 改图 | 工具执行；文件选择在对话内上传面板完成 |
| MV 报价、分镜预览、生成、列表、进度、播放下载、取消、重合成 | 工具执行；图片与完整时间轴通过对话内 MV 编辑器操作；AI 分镜按钮复用现有服务 |
| MV 失败镜头恢复 | retry_music_video_shot：查询逐镜头状态，展示新提示词，确认后保留成功片段继续；待核对任务不能盲目重提 |
| 作品搜索、选择、改名、删除、歌词/音频版本、播放控制 | 工具执行，身份和作品归属仍由后端核验 |
| 分享、下载授权、礼物 | 业务工具及对话内礼物面板，下载仍走真实授权 |
| 艺人主页、作品公开、发现页、收藏点赞 | 业务工具；图片裁剪和浏览使用对话内面板 |
| 头像、登录、账户恢复、隐私、充值、订单、积分 | 工具与对话内专用面板；登录和支付仍由用户实际完成 |
| 图片创作、参考音频分析 | 对话内原生面板；参考音频处理仍遵守既有本地分析规则 |
| 统计、精选管理、资金审核 | 仅打开既有管理面板，原管理员权限仍生效 |

## 本次修复

- 新增失败镜头重试的模型工具参数、意图授权、确认摘要和真实执行。
- MV 查询将原分镜、逐段提示词、失败原因交给对话上下文，避免模型猜测。
- 新增生成预检和指定版本歌词导出。
- 能力目录不再被通用结果列表的 100 项上限截断；能力目录单独允许 200 项。

## 已登记扩展工具

以下从实现类的 capability 声明核对。另有核心 generate_song、generate_lyrics、set_style、modify_section、get_current_song、list_capabilities，以及 share/revoke_share/like/unlike/play/download/cancel。实际可用性取决于启动配置与账号权限。

### AgentAccountActions

`get_credits`, `list_credit_entries`, `get_usage`, `get_account`, `get_referral`, `accept_referral`, `list_orders`, `get_order`, `close_order`, `payment_catalog`, `recharge`, `photo_song`, `login`, `logout`, `account_settings`, `privacy_settings`, `erase_analytics`

### AgentFeatureActions

`open_feature`, `get_artist_profile`, `update_artist_profile`, `set_artist_visibility`, `get_artist_release`, `publish_artist_song`, `remove_artist_song`, `publish_to_discover`, `withdraw_from_discover`, `start_quick_creation`, `get_quick_creation`, `retry_quick_creation`, `get_creative_direction`, `get_lyrics_request`, `confirm_lyrics`, `rewrite_lyric_selection`, `rhyme_lyric_line`, `list_shared_downloads`

### AgentLibraryActions

`create_work`, `list_works`, `select_work`, `rename_work`, `update_work`, `delete_work`, `download_lyrics`, `generation_preflight`, `list_versions`, `select_version`, `replace_lyric_line`, `save_lyrics`, `list_audio`, `select_audio`, `list_covers`, `select_cover`, `edit_cover`, `upload_cover`, `upload_audio`, `discover_music`, `favorite_music`, `unfavorite_music`, `like_sample`, `unlike_sample`, `rewrite_inspiration`, `list_tasks`, `retry_archive`, `close_checking`

### AgentStudioActions

`pause_playback`, `next_track`, `previous_track`, `enable_repeat`, `disable_repeat`, `enable_lyric_follow`, `disable_lyric_follow`, `music_settings_catalog`, `get_music_settings`, `preview_music_settings`, `update_music_settings`, `simplify_music_brief`, `rewrite_lyric_line`, `get_playback_lyrics`, `sync_playback_lyrics`, `list_section_edits`, `section_context`

### AgentAvatarActions

`generate_avatar`

### AgentVideoActions

`video_capabilities`, `create_music_video`, `preview_music_video`, `upload_video_image`, `open_music_video_editor`, `list_music_videos`, `get_music_video`, `retry_music_video_shot`, `retry_music_video_assembly`, `download_music_video`, `cancel_music_video`

### SemanticRetrievalActions

`search_memory`, `index_work_memory`

## 验证边界

自动化测试使用隔离测试环境，不在生产库写入测试夹具。本次不发布、不重启服务，也不更新本地运行 JAR。付费供应商的所有功能没有在本次逐一真实付费调用，不能将自动化回归描述为全功能线上验收。
