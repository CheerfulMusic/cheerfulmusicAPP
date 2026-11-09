-- 首页「热歌推荐」互动计数：播放量、点赞、收藏、评论。
-- 只统计内置试听曲目，track_id 由服务端白名单校验，公开写入不会产生任意行。
-- 评论暂时只有计数通道（没有发布入口），先保留列，避免后续再加表结构。
CREATE TABLE hot_song_metric (
    track_id VARCHAR(64) NOT NULL PRIMARY KEY,
    plays BIGINT NOT NULL DEFAULT 0,
    likes BIGINT NOT NULL DEFAULT 0,
    favorites BIGINT NOT NULL DEFAULT 0,
    comments BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 点赞 / 收藏按创作者去重；未登录的访客不允许写入（由接口层校验身份）。
CREATE TABLE hot_song_reaction (
    track_id VARCHAR(64) NOT NULL,
    creator_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (track_id, creator_id, kind),
    CONSTRAINT fk_hot_song_reaction_creator FOREIGN KEY(creator_id) REFERENCES creator(id)
);
CREATE INDEX idx_hot_song_reaction_creator ON hot_song_reaction(creator_id, kind);
