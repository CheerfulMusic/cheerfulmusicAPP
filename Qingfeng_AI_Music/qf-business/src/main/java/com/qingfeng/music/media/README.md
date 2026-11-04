# media

音频登记、存储容量与授权访问。

已实现MP3/WAV导入、模型音频归档、本地/OSS存储、归属读取、播放/下载内容描述。导入音频标记IMPORTED，模型结果按任务幂等登记为GENERATED。SharedMediaAccess只供sharing校验有效分享后调用，不能直接映射公开接口。

MediaCapacity提供音频和封面共享的创建者容量预留与释放；封面资产及图片加工属于独立cover模块。生成音频与原始歌词版本绑定，不能用另一份歌词创建不匹配的礼物。仓储、HTTP下载和文件适配在infrastructure。
