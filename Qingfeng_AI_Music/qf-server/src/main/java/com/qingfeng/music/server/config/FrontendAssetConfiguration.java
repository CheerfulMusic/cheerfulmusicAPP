package com.qingfeng.music.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FrontendAssetConfiguration implements WebMvcConfigurer {
    private static final Path APP = Path.of("/opt/qingfeng-music");
    private static final int PREVIOUS_RELEASES = 5;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var handler = registry.addResourceHandler("/assets/*-????????.js", "/assets/*-????????.css",
                "/assets/*-????????.woff2", "/assets/*-????????.webp", "/assets/*-????????.png",
                "/assets/*-????????.svg", "/assets/*-????????.jpg")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        // Old tabs can still import lazy chunks after current switches to a new JAR.
        var current = APP.resolve("current/app.jar");
        if (Files.isRegularFile(current)) {
            try {
                handler.addResourceLocations(previousAssetLocations(current.toRealPath()));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot locate retained frontend assets", e);
            }
        }
    }

    static String[] previousAssetLocations(Path currentJar) throws IOException {
        var currentTime = Files.getLastModifiedTime(currentJar);
        try (Stream<Path> releases = Files.list(currentJar.getParent().getParent())) {
            var jars = releases.map(dir -> dir.resolve("app.jar"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.equals(currentJar))
                    .filter(path -> path.toFile().lastModified() < currentTime.toMillis())
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed())
                    .limit(PREVIOUS_RELEASES);
            return jars.map(path -> "jar:" + path.toUri() + "!/BOOT-INF/classes/static/assets/")
                    .toArray(String[]::new);
        }
    }
}
