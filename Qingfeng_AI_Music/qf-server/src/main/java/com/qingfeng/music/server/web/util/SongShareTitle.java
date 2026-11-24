package com.qingfeng.music.server.web.util;

public final class SongShareTitle {
    public static final String SITE_NAME = "青风音乐 Cheerful Music";

    private SongShareTitle() {}

    public static String forTimeline(String song, String publicArtist) {
        return SITE_NAME + "｜" + song + " · " + publicArtist;
    }
}
