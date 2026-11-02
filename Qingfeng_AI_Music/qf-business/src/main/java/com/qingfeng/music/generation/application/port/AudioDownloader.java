package com.qingfeng.music.generation.application.port;
import java.io.*;
public interface AudioDownloader {
    InputStream open(String url) throws IOException;
}
