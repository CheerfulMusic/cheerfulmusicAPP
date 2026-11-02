package com.qingfeng.music.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MusicApplication {
    public static void main(String[] args) { SpringApplication.run(MusicApplication.class, args); }
}
