package com.qingfeng.music.infrastructure.persistence.generation;
import com.qingfeng.music.generation.application.TaskRepository;
import com.qingfeng.music.generation.api.GenerationApi.Task;
import com.qingfeng.music.generation.domain.GenerationStatus;
import java.util.*;
public class MyBatisTaskRepository implements TaskRepository {
 private final TaskMapper mapper;
 public MyBatisTaskRepository(TaskMapper mapper){this.mapper=mapper;}
 static Task toTask(TaskRow r){return new Task(r.id,r.workId,r.revisionId,r.workVersion,r.requestKey,r.title,r.lyrics,r.style,r.provider,GenerationStatus.valueOf(r.status),r.createdAt,r.assetId,r.lastErrorCode,r.updatedAt,r.nextAttemptAt,r.vocalGender);}
 public Task insertOrRead(Task t){var r=new TaskRow();r.id=t.id();r.workId=t.workId();r.revisionId=t.revisionId();r.workVersion=t.workVersion();r.requestKey=t.requestKey();r.title=t.title();r.lyrics=t.lyrics();r.style=t.style();r.provider=t.provider();r.status=t.status().name();r.createdAt=t.createdAt();r.nextAttemptAt=t.nextAttemptAt();r.vocalGender=t.vocalGender();mapper.insertOrIgnore(r);return toTask(mapper.find(t.workId(),t.requestKey()));}
 public Optional<Task> findRequest(String work,String key){return Optional.ofNullable(mapper.find(work,key)).map(MyBatisTaskRepository::toTask);}
 public List<Task> list(String work){return mapper.list(work).stream().map(MyBatisTaskRepository::toTask).toList();}
 public boolean cancelQueued(String work,String task){return mapper.cancel(work,task)==1;}
}
