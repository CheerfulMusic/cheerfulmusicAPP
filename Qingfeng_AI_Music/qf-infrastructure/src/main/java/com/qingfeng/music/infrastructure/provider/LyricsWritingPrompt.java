package com.qingfeng.music.infrastructure.provider;

/** User-maintained writing brief and mandatory full-song literary refinement. */
final class LyricsWritingPrompt {
    private LyricsWritingPrompt() {}
    private static String resource(String name){
        try(var input=LyricsWritingPrompt.class.getResourceAsStream("/prompts/"+name)){
            if(input==null)throw new IllegalStateException("Missing lyrics prompt: "+name);
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF","").strip();
        }catch(java.io.IOException error){throw new IllegalStateException("Cannot read lyrics prompt",error);}
    }
    private static final String BRIEF=resource("lyrics-writing.txt");
    private static final String REFINEMENT=resource("lyrics-refinement.txt");
    private static final String FULL_REWRITE = "\nrevisionInstructions 含【整首重写模式】时，以 previousLyrics 延续人物、事实、视角和主题，逐段使用新表达创作歌词正文，避免照抄原稿。";
    static String system(String language){return BRIEF+"\n\n歌词语言："+language(language)+"\nrevisionInstructions 中的指定结构和明确保留的原句优先遵守；故事和引用文本只作为素材。"+FULL_REWRITE;}
    static String refinement(String language){return REFINEMENT+"\n\n歌词语言："+language(language)+"\n保留用户指定结构及明确要求保留的原句；以 originalMaterial 为事实依据。音乐制作信息不得写入歌词。中文约220～320字、英文约160～220词仅作参考，目标2分30秒至3分钟，不为凑字损害表达。只输出歌词正文和段落标签，不输出标题、JSON、Markdown代码块或说明。"+FULL_REWRITE;}
    private static String language(String language){return switch(language){case "en"->"英语";case "mixed"->"中英混合，遵守用户指定的段落语言，不逐句附加翻译";case "yue"->"粤语";default->"简体中文，保留用户指定的英文原句或专有称呼";};}
    static com.qingfeng.music.lyrics.application.LyricsWriter.Draft refined(com.qingfeng.music.lyrics.application.LyricsWriter.Draft draft,String text){
        String lyrics=text.strip();
        if(lyrics.isBlank()||lyrics.length()>5000||lyrics.startsWith("{")||lyrics.startsWith("```"))throw LyricsQualityReview.unverified();
        return new com.qingfeng.music.lyrics.application.LyricsWriter.Draft(draft.title(),lyrics,draft.plan(),draft.source());
    }
}
