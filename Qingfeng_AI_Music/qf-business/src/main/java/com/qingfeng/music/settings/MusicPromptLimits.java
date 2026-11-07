package com.qingfeng.music.settings;

/** 预检、编译与提交共享供应商输入预算。 */
public record MusicPromptLimits(int title, int lyrics, int style, int negativeTags) {
    public MusicPromptLimits {
        if (title < 1 || lyrics < 1 || style < 1 || negativeTags < 1)
            throw new IllegalArgumentException("音乐输入预算必须为正数");
    }
    public static MusicPromptLimits suno() { return new MusicPromptLimits(80, 5000, 1000, 500); }
}
