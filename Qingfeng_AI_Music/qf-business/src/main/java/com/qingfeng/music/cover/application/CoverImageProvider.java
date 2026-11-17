package com.qingfeng.music.cover.application;

import java.io.IOException;

/** Text-to-image provider for automatic song covers. */
public interface CoverImageProvider {
    String generate(String prompt) throws IOException, InterruptedException;
    default String edit(byte[] image, String prompt) throws IOException, InterruptedException {
        throw new Rejected(501);
    }

    /** The provider explicitly rejected the request. Transport failures leave the outcome unknown. */
    final class Rejected extends IOException {
        private final int status;

        public Rejected(int status) {
            super("Image request rejected");
            this.status = status;
        }

        public int status() { return status; }
    }
}
