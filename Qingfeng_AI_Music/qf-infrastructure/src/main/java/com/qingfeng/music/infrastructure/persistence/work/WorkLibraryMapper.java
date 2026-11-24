package com.qingfeng.music.infrastructure.persistence.work;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkLibraryMapper {
    record SearchRow(String id, long version, String title, String story, String message,
                     String lyricTitle, String lyrics, String revisionId, int lyricNumber) {}

    record ProjectionRow(String id, String quickStatus, String taskStatus, int activeTasks, int unknownTasks,
                         int lyricCount, String audioId, String audioOrigin, String songTitle,
                         String coverId, String videoId, String videoState) {}

    List<SearchRow> search(@Param("owner") String owner, @Param("pattern") String pattern,
                           @Param("exact") String exact, @Param("offset") int offset);

    List<ProjectionRow> selectProjection(@Param("owner") String owner, @Param("workIds") List<String> workIds);
}
