package com.qingfeng.music.cover.application;

import java.io.IOException;

@FunctionalInterface
public interface CoverTitleVerifier {
    boolean matches(byte[] image, String title) throws IOException, InterruptedException;
}
