package com.qingfeng.music.work.api;

import java.time.Instant;
import java.util.List;

public interface WorkApi {
    Work create(String creatorId, Draft draft);
    Work create(String creatorId, Draft draft, String draftKey);
    Work update(String creatorId, String workId, long expectedVersion, Draft draft);
    Work get(String creatorId, String workId);
    List<Work> list(String creatorId, int page);
    void delete(String creatorId, String workId, boolean acknowledgeCompleted);
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
