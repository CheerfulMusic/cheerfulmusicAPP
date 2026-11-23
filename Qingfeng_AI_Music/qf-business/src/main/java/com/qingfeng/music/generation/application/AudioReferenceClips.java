package com.qingfeng.music.generation.application;

import java.net.URI;
import java.util.Optional;

/** Only approved sample clips may enter the generation queue. The queue stores an object key, never a URL. */
public interface AudioReferenceClips {
    record Clip(String objectKey, double weight) {}

    default boolean schemaAvailable() { return false; }
    default Optional<Clip> selected(String trackId) { return Optional.empty(); }
    default Optional<Clip> frozen(String taskId) { return Optional.empty(); }
    default void freeze(String taskId, Clip clip) { throw new UnsupportedOperationException(); }
    default URI sign(Clip clip) { throw new UnsupportedOperationException(); }

    AudioReferenceClips NONE = new AudioReferenceClips() {};
}
