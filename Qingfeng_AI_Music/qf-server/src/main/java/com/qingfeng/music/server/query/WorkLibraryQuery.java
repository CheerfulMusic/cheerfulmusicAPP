package com.qingfeng.music.server.query;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.work.WorkLibraryMapper;
import com.qingfeng.music.infrastructure.persistence.work.WorkLibraryMapper.ProjectionRow;
import com.qingfeng.music.work.api.WorkApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 作品列表的只读投影，一次批量读取当前页状态，不逐个作品查询三个子模块。 */
@Component
@Profile({"mysql", "test"})
public final class WorkLibraryQuery {
    public record Item(WorkApi.Work work, String status, String audioId, String audioOrigin, String songTitle,
                       String coverId, String videoId, String videoState, WorkSearchEvidence evidence) {}

    private final WorkApi works;
    private final WorkLibraryMapper mapper;

    public WorkLibraryQuery(WorkApi works, WorkLibraryMapper mapper) {
        this.works = works;
        this.mapper = mapper;
    }

    public List<Item> list(String owner, int page) {
        return project(owner, works.list(owner, page), Map.of());
    }

    /** Literal keyword query, scoped before pagination. User '%' and '_' never become wildcards. */
    public List<Item> search(String owner, String query, int page) {
        if (query == null || query.isBlank()) return list(owner, page);
        if (page < 0 || page > 10000 || query.length() > 200) {
            throw new DomainException("INVALID_SEARCH", "关键词最多200字，页码必须有效。");
        }
        String pattern = "%" + query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_").toLowerCase(Locale.ROOT) + "%";
        String keyword = query.strip();
        String exact = keyword.toLowerCase(Locale.ROOT);
        var matches = mapper.search(owner, pattern, exact, Math.multiplyExact(page, 20));

        // Revalidate ownership after search, before exposing a projection.
        var owned = new ArrayList<WorkApi.Work>();
        var evidence = new HashMap<String, WorkSearchEvidence>();
        for (var match : matches) {
            try {
                var work = works.get(owner, match.id());
                if (work.version() == match.version()) {
                    owned.add(work);
                    var material = new WorkSearchEvidence.Material(match.title(), match.story(), match.message(),
                        match.lyricTitle(), match.lyrics(), match.revisionId(), match.lyricNumber());
                    evidence.put(work.id(), material.literal(keyword));
                }
            } catch (DomainException ignored) {}
        }
        return project(owner, owned, evidence);
    }

    private List<Item> project(String owner, List<WorkApi.Work> owned, Map<String, WorkSearchEvidence> evidence) {
        if (owned.isEmpty()) return List.of();
        var workIds = owned.stream().map(WorkApi.Work::id).toList();
        var byId = new HashMap<String, ProjectionRow>();
        for (var row : mapper.selectProjection(owner, workIds)) {
            byId.put(row.id(), row);
        }
        return owned.stream().filter(work -> byId.containsKey(work.id())).map(work -> {
            var row = byId.get(work.id());
            return new Item(work, status(row), row.audioId(), row.audioOrigin(), row.songTitle(),
                row.coverId(), row.videoId(), row.videoState(), evidence.get(work.id()));
        }).toList();
    }

    private static String status(ProjectionRow row) {
        String quick = row.quickStatus();
        if (row.activeTasks() > 0 || List.of("QUEUED", "WRITING", "LYRICS_READY", "SUBMITTING_MUSIC").contains(quick == null ? "" : quick))
            return "GENERATING";
        if (row.unknownTasks() > 0 || "SUBMISSION_UNKNOWN".equals(quick)) return "CHECKING";
        if (row.audioId() != null) return "COMPLETED";
        if ("FAILED".equals(row.taskStatus()) || "FAILED".equals(quick)) return "GENERATION_FAILED";
        return row.lyricCount() > 0 ? "READY_TO_GENERATE" : "LYRICS_DRAFT";
    }
}
