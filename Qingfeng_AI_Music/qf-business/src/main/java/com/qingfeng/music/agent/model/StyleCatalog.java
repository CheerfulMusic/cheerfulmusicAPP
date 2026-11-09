package com.qingfeng.music.agent.model;

import java.util.List;
import java.util.Set;

/**
 * 站内风格白名单。必须与前端 src/modules/studio/model/suggestions.ts 的
 * styleSuggestions + 四个首页主题 + 说唱嘻哈保持一致；这里是同一套规则的 Java 镜像，
 * 不另造第二套风格体系。前端仍会用 styleFromInspiration 再兜一次。
 */
public final class StyleCatalog {
    private StyleCatalog() {}

    public static final String FALLBACK = "温暖民谣";

    public static final List<String> PRESETS = List.of(
        "温暖民谣", "抒情流行", "轻柔爵士", "浪漫钢琴", "治愈轻音乐", "清新校园",
        "国风古韵", "梦幻电子", "轻快流行", "复古港风", "R&B节奏蓝调", "独立摇滚",
        "柔和声乐", "电影配乐", "浪漫弦乐", "轻快乡村", "甜蜜情歌", "童趣儿歌",
        "舒缓Lo-fi", "清新波萨诺瓦", "热情拉丁", "轻快说唱", "悠扬古典", "空灵氛围");

    public static final List<String> THEMES = List.of(
        "爱情主题", "家庭与陪伴", "自由随性 · 大海旅行", "Disco舞曲");

    public static final String RAP = "说唱嘻哈";

    private static final Set<String> ALLOWED = Set.copyOf(concat());

    private static List<String> concat() {
        var builder = new java.util.ArrayList<String>(PRESETS);
        builder.addAll(THEMES);
        builder.add(RAP);
        return builder;
    }

    /** 候选风格列表，用于系统提示词里约束模型只能从中选择。 */
    public static List<String> candidates() {
        return List.copyOf(ALLOWED);
    }

    public static boolean allows(String style) {
        return style != null && ALLOWED.contains(style.trim());
    }

    /** 非法或缺失一律回落到合法值，保证下游草稿风格永远可用。 */
    public static String safe(String style) {
        return allows(style) ? style.trim() : FALLBACK;
    }
}
