# sharing

收礼链接与公开内容投影。

已实现主动创建分享、白名单快照、7天有效期、令牌哈希存储、按作品全部撤销；每次公开信息及音频请求都校验有效性。只通过WorkApi/LyricsApi/MediaApi和SharedMediaAccess交互，ShareRepository由infrastructure适配。已经下载的内容不能通过撤销收回。
