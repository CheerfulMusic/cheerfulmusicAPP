package com.qingfeng.music.lyrics.application.port;

public interface TextGenerationProvider {
    boolean isConfigured();
    Draft generate(Prompt prompt);
    record Prompt(String recipient, String occasion, String story, String message, String style) {}
    record Draft(String title, String lyrics) {}
}
