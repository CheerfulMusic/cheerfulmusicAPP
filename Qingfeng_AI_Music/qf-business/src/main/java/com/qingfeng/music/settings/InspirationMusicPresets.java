package com.qingfeng.music.settings;

import com.qingfeng.music.work.api.VocalGender;
import java.util.*;

/** Exact music directions for the five inspiration tags. Descriptions are not lyrics. */
public final class InspirationMusicPresets {
    private InspirationMusicPresets() {}
    public record Preset(String label, String prompt, String voice, Map<String,List<String>> choices) {
        public MusicSettings.Preferences preferences(String vocalGender) {
            return new MusicSettings.Preferences(1, null, choices, VocalGender.normalize(vocalGender), label, "");
        }
        public boolean matches(Map<String,List<String>> selected) {
            return choices.entrySet().stream().allMatch(entry -> entry.getValue().equals(selected.getOrDefault(entry.getKey(), List.of())))
                && selected.entrySet().stream().filter(entry -> !entry.getKey().equals("exclude"))
                .allMatch(entry -> entry.getValue().equals(choices.getOrDefault(entry.getKey(), List.of())));
        }
        public String promptForVoice(String input) {
            String selected = VocalGender.normalize(input);
            if (selected.equals(voice)) return prompt;
            if (selected.equals("AUTO")) return prompt.replaceAll("(?i)(?<![a-z])(?:female|male) (?=vocals?)", "");
            String gender = selected.equals("MALE") ? "male" : "female";
            if (prompt.matches("(?is).*(?<![a-z])(?:female|male) vocals?.*"))
                return prompt.replaceAll("(?i)(?<![a-z])(?:female|male) (?=vocals?)", gender + " ");
            return prompt + ". " + (selected.equals("MALE") ? "Male" : "Female") + " lead vocals.";
        }
    }
    private static final List<Preset> PRESETS = List.of(
        new Preset("流行抒情",
            "Mandopop, gentle piano solo and soft cinematic strings, subtle stereo delay on the vocal and piano, slow unhurried tempo, clear gentle female vocal with intimate verses and softly lifted chorus",
            "FEMALE", Map.of("genre", List.of("pop"), "instruments", List.of("piano", "strings"), "delivery", List.of("gentle"), "timbre", List.of("clear"), "tempo", List.of("slow"), "arrangement", List.of("space"))),
        new Preset("流行摇滚",
            "Hard rock, aggressive electric guitar riffs, heavy distortion, powerful power chords, driving bass, explosive live drums, punchy snare, gritty raspy male vocals, intense emotional delivery, restrained verses, explosive anthemic choruses, passionate guitar solo, raw energy, stadium rock atmosphere, loud and powerful.",
            "MALE", Map.of("genre", List.of("rock"), "instruments", List.of("bass", "drums"), "delivery", List.of("powerful", "husky"), "arrangement", List.of("live"))),
        new Preset("蓝调R&B",
            "light falsetto, jazzy R&B,Electronic\nlaid-back groove, conversational phrasing,\nintimate and close, minimal exaggeration,\nneo-soul influence, warm emotional delivery，Male vocal, gentle and smooth tone,",
            "MALE", Map.of("genre", List.of("rnb", "electronic"), "delivery", List.of("natural", "airy"), "timbre", List.of("warm"), "groove", List.of("relaxed"))),
        new Preset("华语民谣",
            "Indie folk ballad, close-miked fingerpicked steel-string guitar with flowing arpeggios and a delicate intro; high, slightly husky, crooning legato; unhurried gentle pulse, soft rhythmic acoustic strumming in the chorus; warm intimate mix, guitar-led throughout, smooth singable melody",
            "AUTO", Map.of("genre", List.of("folk"), "instruments", List.of("guitar"), "delivery", List.of("husky"), "tempo", List.of("slow"), "arrangement", List.of("clean"))),
        new Preset("中国风",
            "Poetic Chinese pop, ancient Chinese aesthetics, elegant oriental melody, pentatonic scale, guzheng, pipa, bamboo flute, soft piano, subtle strings, gentle acoustic guitar, melancholic atmosphere, literary Chinese lyrics, classical poetry imagery, nostalgic storytelling, delicate male vocal, restrained emotional delivery, graceful melodic progression, minimalist arrangement, dreamy and bittersweet mood.",
            "MALE", Map.of("genre", List.of("chinese", "pop"), "instruments", List.of("guzheng", "flute", "piano"), "delivery", List.of("gentle"), "emotion", List.of("sad", "romantic"), "arrangement", List.of("clean")))
    );
    public static List<Preset> all() { return PRESETS; }
    public static Optional<Preset> find(String label) {
        return label == null ? Optional.empty() : PRESETS.stream().filter(preset -> preset.label().equals(label.trim())).findFirst();
    }
}
