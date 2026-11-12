package com.qingfeng.music.server.avatar;

import java.io.InputStream;

/** Submission may have charged even when it fails; callers must never resubmit automatically. */
public interface AvatarImageProvider {
    /** Provider definitively refused the request or it never crossed the network boundary. */
    class Rejected extends RuntimeException {}
    record Result(String status, String imageUrl) {}
    boolean configured();
    String submit(String prompt);
    Result query(String taskId);
    InputStream download(String imageUrl) throws java.io.IOException;
}
