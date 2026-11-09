package com.qingfeng.music.work.application;

import com.qingfeng.music.work.api.WorkApi.*;
import java.util.*;

public interface WorkRepository {
    boolean lockActiveOwner(String owner);
    void insert(String owner, Work work);
    boolean update(String owner, Work work, long expectedVersion);
    Optional<Work> find(String owner, String id);
    List<Work> list(String owner, int offset, int limit);
    void delete(String owner, String id);
}
