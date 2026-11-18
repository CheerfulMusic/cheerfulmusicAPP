package com.qingfeng.music.server.hotsong;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Curated songs supplied by the site owner. Object names include a content hash. */
public final class RecommendationSamples {
    private RecommendationSamples() {}
    public record Song(String id, String title, String genre, String audio) {}
    public static final List<Song> SONGS = List.of(
        new Song("feng-ye-xing", "风夜行", "华语流行", "/media/sample-feng-ye-xing-v1-preview-128.mp3"),
        new Song("ming-chui-si-hai", "灵玥 名垂四海", "华语流行", "/media/sample-ming-chui-si-hai-v1-preview-128.mp3"),
        new Song("liu-lang-shi-ren", "流浪诗人", "华语流行", "/media/sample-liu-lang-shi-ren-v1-preview-128.mp3"),
        new Song("luo-yu-de-cao-chang", "落雨的操场", "华语流行", "/media/sample-luo-yu-de-cao-chang-v1-preview-128.mp3"),
        new Song("mei-jian-fu-yun", "眉间浮云", "华语流行", "/media/sample-mei-jian-fu-yun-v1-preview-128.mp3"),
        new Song("wu-ren-dai-ti", "无人代替", "华语流行", "/media/sample-wu-ren-dai-ti-v1-preview-128.mp3"),
        new Song("wu-ren-qu", "无人区", "华语流行", "/media/sample-wu-ren-qu-v1-preview-128.mp3"),
        new Song("wu-ye-zhi-shi", "午夜之时", "华语流行", "/media/sample-wu-ye-zhi-shi-v1-preview-128.mp3"),
        new Song("meng-de-chi-bang", "梦的翅膀受了伤", "电子", "/media/sample-meng-de-chi-bang-v1-preview-128.mp3"),
        new Song("after-midnight", "AFTER MIDNIGHT", "欧美流行", "/media/sample-after-midnight-v1-preview-128.mp3"),
        new Song("if-you-wanna-feel-me", "if you wanna feel me", "欧美流行", "/media/sample-if-you-wanna-feel-me-v1-preview-128.mp3"),
        new Song("where-the-wind-goes", "Where the Wind Goes", "欧美流行", "/media/sample-where-the-wind-goes-v1-preview-128.mp3"));

    private static final Map<String, String> OBJECTS = Map.ofEntries(
        Map.entry("sample-feng-ye-xing-v1.mp3", "qingfeng/recommendation-samples/20261118/original/686329f818bc/sample-feng-ye-xing-v1.mp3"),
        Map.entry("sample-feng-ye-xing-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/22c66d4eb1ca/sample-feng-ye-xing-v1-preview-128.mp3"),
        Map.entry("sample-feng-ye-xing-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/2c2b7a5895fd/sample-feng-ye-xing-v1-cover.jpg"),
        Map.entry("sample-ming-chui-si-hai-v1.mp3", "qingfeng/recommendation-samples/20261118/original/a232d55b2928/sample-ming-chui-si-hai-v1.mp3"),
        Map.entry("sample-ming-chui-si-hai-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/b93e1b37649a/sample-ming-chui-si-hai-v1-preview-128.mp3"),
        Map.entry("sample-ming-chui-si-hai-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/d13cad0bd45d/sample-ming-chui-si-hai-v1-cover.jpg"),
        Map.entry("sample-liu-lang-shi-ren-v1.mp3", "qingfeng/recommendation-samples/20261118/original/50e10320b7af/sample-liu-lang-shi-ren-v1.mp3"),
        Map.entry("sample-liu-lang-shi-ren-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/38982b6e494a/sample-liu-lang-shi-ren-v1-preview-128.mp3"),
        Map.entry("sample-liu-lang-shi-ren-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/00f6b6e417d8/sample-liu-lang-shi-ren-v1-cover.jpg"),
        Map.entry("sample-luo-yu-de-cao-chang-v1.mp3", "qingfeng/recommendation-samples/20261118/original/4f62652ce53d/sample-luo-yu-de-cao-chang-v1.mp3"),
        Map.entry("sample-luo-yu-de-cao-chang-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/0220c9dc57c5/sample-luo-yu-de-cao-chang-v1-preview-128.mp3"),
        Map.entry("sample-luo-yu-de-cao-chang-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/dd030f7cfbd2/sample-luo-yu-de-cao-chang-v1-cover.jpg"),
        Map.entry("sample-mei-jian-fu-yun-v1.mp3", "qingfeng/recommendation-samples/20261118/original/7532a1af5c88/sample-mei-jian-fu-yun-v1.mp3"),
        Map.entry("sample-mei-jian-fu-yun-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/8d073ed8655f/sample-mei-jian-fu-yun-v1-preview-128.mp3"),
        Map.entry("sample-mei-jian-fu-yun-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/7232cc6e8753/sample-mei-jian-fu-yun-v1-cover.jpg"),
        Map.entry("sample-wu-ren-dai-ti-v1.mp3", "qingfeng/recommendation-samples/20261118/original/9f0049f70a05/sample-wu-ren-dai-ti-v1.mp3"),
        Map.entry("sample-wu-ren-dai-ti-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/b935566a8e41/sample-wu-ren-dai-ti-v1-preview-128.mp3"),
        Map.entry("sample-wu-ren-dai-ti-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/39c83ab25ca7/sample-wu-ren-dai-ti-v1-cover.jpg"),
        Map.entry("sample-wu-ren-qu-v1.mp3", "qingfeng/recommendation-samples/20261118/original/91472125aa2d/sample-wu-ren-qu-v1.mp3"),
        Map.entry("sample-wu-ren-qu-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/44ba19c8f10c/sample-wu-ren-qu-v1-preview-128.mp3"),
        Map.entry("sample-wu-ren-qu-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/c8c72b5bc21f/sample-wu-ren-qu-v1-cover.jpg"),
        Map.entry("sample-wu-ye-zhi-shi-v1.mp3", "qingfeng/recommendation-samples/20261118/original/a43f1d007364/sample-wu-ye-zhi-shi-v1.mp3"),
        Map.entry("sample-wu-ye-zhi-shi-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/85caa0fbde16/sample-wu-ye-zhi-shi-v1-preview-128.mp3"),
        Map.entry("sample-wu-ye-zhi-shi-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/4338828721de/sample-wu-ye-zhi-shi-v1-cover.jpg"),
        Map.entry("sample-meng-de-chi-bang-v1.mp3", "qingfeng/recommendation-samples/20261118/original/9eb39b442789/sample-meng-de-chi-bang-v1.mp3"),
        Map.entry("sample-meng-de-chi-bang-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/604cf455628c/sample-meng-de-chi-bang-v1-preview-128.mp3"),
        Map.entry("sample-meng-de-chi-bang-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/d6970859a663/sample-meng-de-chi-bang-v1-cover.jpg"),
        Map.entry("sample-after-midnight-v1.mp3", "qingfeng/recommendation-samples/20261118/original/2420e68a6615/sample-after-midnight-v1.mp3"),
        Map.entry("sample-after-midnight-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/81f05f0bc210/sample-after-midnight-v1-preview-128.mp3"),
        Map.entry("sample-after-midnight-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/96c87fa9ce5d/sample-after-midnight-v1-cover.jpg"),
        Map.entry("sample-if-you-wanna-feel-me-v1.mp3", "qingfeng/recommendation-samples/20261118/original/55e9c6484abd/sample-if-you-wanna-feel-me-v1.mp3"),
        Map.entry("sample-if-you-wanna-feel-me-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/d7a36f814716/sample-if-you-wanna-feel-me-v1-preview-128.mp3"),
        Map.entry("sample-if-you-wanna-feel-me-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/912f661b3f41/sample-if-you-wanna-feel-me-v1-cover.jpg"),
        Map.entry("sample-where-the-wind-goes-v1.mp3", "qingfeng/recommendation-samples/20261118/original/a52c6ce07d83/sample-where-the-wind-goes-v1.mp3"),
        Map.entry("sample-where-the-wind-goes-v1-preview-128.mp3", "qingfeng/recommendation-samples/20261118/audio/0c02a67bf367/sample-where-the-wind-goes-v1-preview-128.mp3"),
        Map.entry("sample-where-the-wind-goes-v1-cover.jpg", "qingfeng/recommendation-samples/20261118/cover/df8ceefe125e/sample-where-the-wind-goes-v1-cover.jpg"));

    public static Optional<String> objectKey(String filename) {
        return Optional.ofNullable(OBJECTS.get(filename));
    }
}
