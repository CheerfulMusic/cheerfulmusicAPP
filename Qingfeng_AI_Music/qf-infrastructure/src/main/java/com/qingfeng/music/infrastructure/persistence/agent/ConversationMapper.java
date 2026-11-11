package com.qingfeng.music.infrastructure.persistence.agent;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** 注解式 SQL，与 TaskMapper 风格一致；由 BusinessConfiguration 的 @MapperScan 扫描。 */
@Mapper
public interface ConversationMapper {

    @Select("SELECT * FROM agent_conversation WHERE creator_id=#{creator}")
    ConversationRow findByCreator(@Param("creator") String creator);

    @Insert("INSERT INTO agent_conversation(id,creator_id,current_work_id,created_at,updated_at) "
            + "VALUES(#{id},#{creator},NULL,#{now},#{now})")
    int insert(@Param("id") String id, @Param("creator") String creator, @Param("now") Instant now);

    @Select("SELECT COALESCE(MAX(seq),0)+1 FROM agent_message WHERE conversation_id=#{conversation}")
    long nextSeq(@Param("conversation") String conversation);

    @Insert("INSERT INTO agent_message(id,conversation_id,seq,role,kind,state,content_json,task_id,created_at) "
            + "VALUES(#{id},#{conversationId},#{seq},#{role},#{kind},#{state},#{contentJson},#{taskId},#{createdAt})")
    int insertMessage(MessageRow row);

    @Select("SELECT * FROM agent_message WHERE conversation_id=#{conversation} ORDER BY seq")
    List<MessageRow> listMessages(@Param("conversation") String conversation);

    @Update("UPDATE agent_conversation SET current_work_id=#{work},updated_at=#{now} WHERE id=#{conversation}")
    int updateCurrentWork(@Param("conversation") String conversation, @Param("work") String work,
                          @Param("now") Instant now);

    @Select("SELECT * FROM agent_conversation_state WHERE conversation_id=#{conversation}")
    StateRow findState(@Param("conversation") String conversation);

    @Insert("INSERT INTO agent_conversation_state(conversation_id,current_song_json,revision,updated_at) "
            + "VALUES(#{conversation},NULL,0,#{now})")
    int insertState(@Param("conversation") String conversation, @Param("now") Instant now);

    @Update("UPDATE agent_conversation_state SET current_song_json=#{json},revision=revision+1,updated_at=#{now} "
            + "WHERE conversation_id=#{conversation} AND revision=#{expected}")
    int updateState(@Param("conversation") String conversation, @Param("json") String json,
                    @Param("expected") long expected, @Param("now") Instant now);

    @Delete("DELETE FROM agent_message WHERE conversation_id=#{conversation}")
    int deleteMessages(@Param("conversation") String conversation);

    @Update("UPDATE agent_conversation SET current_work_id=NULL,updated_at=#{now} WHERE id=#{conversation}")
    int clearWork(@Param("conversation") String conversation, @Param("now") Instant now);

    @Update("UPDATE agent_conversation_state SET current_song_json=NULL,revision=revision+1,updated_at=#{now} "
            + "WHERE conversation_id=#{conversation}")
    int clearState(@Param("conversation") String conversation, @Param("now") Instant now);
}
