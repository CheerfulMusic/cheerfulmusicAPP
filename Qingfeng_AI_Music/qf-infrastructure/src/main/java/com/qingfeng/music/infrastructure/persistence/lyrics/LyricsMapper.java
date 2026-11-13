package com.qingfeng.music.infrastructure.persistence.lyrics;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface LyricsMapper {
    @Select("SELECT custom_sections FROM lyric_revision WHERE 1=0")
    String probeCustomSectionsColumn();

    @Insert("""
        <script>
        INSERT INTO lyric_revision(id,work_id,work_version,title,lyrics,source,confirmed,created_at,language,template_id,chorus_first
            <if test="customSections != null">,custom_sections</if>)
        VALUES(#{id},#{workId},#{workVersion},#{title},#{lyrics},#{source},#{confirmed},#{createdAt},#{language},#{templateId},#{chorusFirst}
            <if test="customSections != null">,#{customSections}</if>)
        </script>
        """)
    void insert(LyricsRow row);
    @Select("SELECT * FROM lyric_revision WHERE work_id=#{work} AND id=#{id}") LyricsRow find(@Param("work") String work,@Param("id") String id);
    @Select("SELECT * FROM lyric_revision WHERE work_id=#{work} ORDER BY created_at DESC,id DESC LIMIT 100") List<LyricsRow> list(@Param("work") String work);
    @Update("UPDATE lyric_revision SET confirmed=TRUE WHERE work_id=#{work} AND id=#{id}") void confirm(@Param("work") String work,@Param("id") String id);
}
