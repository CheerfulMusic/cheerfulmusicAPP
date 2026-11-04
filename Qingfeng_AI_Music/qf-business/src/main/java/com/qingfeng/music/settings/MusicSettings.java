package com.qingfeng.music.settings;

import java.util.*;
import com.qingfeng.music.work.api.WorkApi.Work;

/** 分类偏好与提交描述分别保存，历史任务不依赖当前目录重新解释。 */
public interface MusicSettings {
    record Preferences(int version,String recipeId,Map<String,List<String>> choices,String vocalGender,String freeText,String negativeText){}
    record Option(String id,String zh,String en,String prompt){}
    record Group(String id,String zh,String en,int max,List<Option> options){}
    record Recipe(String id,String zh,String en,Map<String,List<String>> choices){}
    record Catalog(int version,List<Group> groups,List<Recipe> recipes){}
    record Direction(Preferences preferences,String style,String negativeTags,String summaryZh,String summaryEn,int catalogVersion){}
    record View(Work work,Direction direction){}
    record Saved(String style,String vocalGender,Preferences preferences){}
    interface Repository {
        long lockVersion(String work);
        Optional<Saved> find(String work);
        void save(String work,Saved settings);
        void snapshot(String task,Direction settings);
        Optional<Direction> task(String work,String task);
    }
    Catalog catalog();
    Direction preview(Preferences preferences);
    View get(String owner,String work);
    View save(String owner,String work,long expectedVersion,Preferences preferences);
    Direction resolve(String owner,Work work);
    void snapshot(String task,Direction direction);
    Optional<Direction> frozen(String work,String task);
    Optional<Direction> task(String owner,String work,String task);
}
