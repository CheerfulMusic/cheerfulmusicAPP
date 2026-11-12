package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentSocialActions;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Set;

/** Conversation actions delegate to the same ownership/version checks as the existing work page. */
@Service
@Profile({"mysql", "test"})
public class DefaultAgentSocialActions implements AgentSocialActions {
    private static final Set<GenerationStatus> TERMINAL = Set.of(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED, GenerationStatus.CANCELLED);
    private final WorkApi works;
    private final SharingApi shares;
    private final MediaApi media;
    private final GenerationApi generation;
    private final AgentWorkReactions reactions;

    public DefaultAgentSocialActions(WorkApi works, SharingApi shares, MediaApi media, GenerationApi generation, AgentWorkReactions reactions) {
        this.works = works;
        this.shares = shares;
        this.media = media;
        this.generation = generation;
        this.reactions = reactions;
    }

    @Override
    public Result execute(String creatorId, String conversationId, String action, String text, String requestKey, SongCard current) {
        if (current == null || current.songId() == null || current.songId().isBlank()) {
            return new Result("还没有选定作品。先写一首歌词或创作一首歌，再继续这个操作。", null, null, current);
        }
        works.get(creatorId, current.songId());
        return switch (action) {
            case "like", "unlike" -> {
                boolean active = reactions.setLiked(creatorId, current.songId(), "like".equals(action));
                yield new Result(active ? "已给这首作品点赞。" : "已取消这首作品的点赞。", action, null, current);
            }
            case "share" -> {
                requireAudio(creatorId, current);
                if (current.revisionId() == null || current.revisionId().isBlank()) {
                    throw new DomainException("AGENT_AUDIO_NOT_READY", "这段音频还没有对应的歌词版本，暂时无法创建分享链接。");
                }
                var created = shares.create(creatorId, current.songId(), current.revisionId(), current.assetId());
                yield new Result("分享链接已创建，有效期为 7 天。持有链接的人可以查看作品标题、歌词、封面与寄语并试听。", "share", "/gift/" + created.token(), current);
            }
            case "revoke_share" -> {
                shares.revokeAll(creatorId, current.songId());
                yield new Result("已撤销这首作品的全部分享链接。", action, null, current);
            }
            case "play", "download" -> {
                requireAudio(creatorId, current);
                yield new Result("play".equals(action) ? "为你播放当前作品。" : "当前作品的音频已准备好，可以下载。", action, null, current);
            }
            case "cancel" -> {
                var task = generation.list(creatorId, current.songId()).stream()
                        .filter(item -> !TERMINAL.contains(item.status()))
                        .max(Comparator.comparing(GenerationApi.Task::createdAt)).orElse(null);
                if (task == null) yield new Result("当前没有正在生成的任务。", null, null, current);
                var cancelled = generation.cancel(creatorId, current.songId(), task.id());
                yield new Result(cancelled.status() == GenerationStatus.CANCELLED ? "已取消当前生成任务。" : "取消请求已处理，任务当前状态：" + cancelled.status(), "cancel", null, current);
            }
            default -> new Result("这个操作暂时还未接入。", null, null, current);
        };
    }

    @Override
    public boolean liked(String creatorId, SongCard current) {
        return current != null && current.songId() != null && !current.songId().isBlank() && reactions.liked(creatorId, current.songId());
    }

    private void requireAudio(String creatorId, SongCard current) {
        if (current.assetId() == null || current.assetId().isBlank() || current.audioUrl() == null || current.audioUrl().isBlank()) {
            throw new DomainException("AGENT_AUDIO_NOT_READY", "这版作品还没有生成完成的音频。请先生成音乐，完成后再操作。");
        }
        media.get(creatorId, current.songId(), current.assetId());
    }
}
