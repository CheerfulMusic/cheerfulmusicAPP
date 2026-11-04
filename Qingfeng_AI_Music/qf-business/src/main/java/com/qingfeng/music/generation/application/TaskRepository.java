package com.qingfeng.music.generation.application;
import com.qingfeng.music.generation.api.GenerationApi.Task;
import java.util.*;
public interface TaskRepository {
 Task insertOrRead(Task task);
 Optional<Task> findRequest(String work,String requestKey);
 List<Task> list(String work);
 default boolean cancelQueued(String work,String task){return false;}
}
