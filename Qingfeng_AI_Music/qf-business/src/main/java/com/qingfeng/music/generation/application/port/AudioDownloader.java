package com.qingfeng.music.generation.application.port;
import java.io.*;
public interface AudioDownloader {
    InputStream open(String url) throws IOException;

    enum Problem { LINK_EXPIRED, URL_NOT_REGISTERED, INVALID_URL, HTTP_STATUS, CONNECT_TIMEOUT, READ_TIMEOUT, DEADLINE, SIZE_LIMIT, INCOMPLETE_BODY, IO }

    final class DownloadFailure extends IOException {
        private final Problem problem;

        public DownloadFailure(Problem problem) { this(problem, null); }
        public DownloadFailure(Problem problem, IOException cause) {
            super("音频下载失败", cause);
            this.problem = problem;
        }
        public Problem problem() { return problem; }
    }
}
