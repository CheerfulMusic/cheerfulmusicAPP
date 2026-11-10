package com.qingfeng.music.work.api;

import java.time.Instant;
import java.util.List;

public interface WorkApi {
    Work create(String creatorId, Draft draft);
    Work create(String creatorId, Draft draft, String draftKey);
    Work update(String creatorId, String workId, long expectedVersion, Draft draft);
    Work get(String creatorId, String workId);
    List<Work> list(String creatorId, int page);
    /** 删除作品及其全部关联数据（歌词、生成任务、音频、封面、分享等），返回被删除的作品以便前端确认。 */
    Work delete(String creatorId, String workId);
    record Draft(String recipient, String occasion, String story, String message, String style, String title,String vocalGender) {
        public Draft {vocalGender=VocalGender.normalize(vocalGender);}
        public Draft(String recipient,String occasion,String story,String message,String style,String title){this(recipient,occasion,story,message,style,title,"AUTO");}
    }
    /** API对象不暴露内部owner、会话或供应商信息。 */
    record Work(String id, String recipient, String occasion, String story, String message, String style, String title, long version, Instant createdAt, Instant updatedAt,String vocalGender) {
        public Work {vocalGender=VocalGender.normalize(vocalGender);}
        public Work(String id,String recipient,String occasion,String story,String message,String style,String title,long version,Instant createdAt,Instant updatedAt){this(id,recipient,occasion,story,message,style,title,version,createdAt,updatedAt,"AUTO");}
    }
}
