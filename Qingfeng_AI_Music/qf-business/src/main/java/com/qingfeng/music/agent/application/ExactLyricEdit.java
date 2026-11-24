package com.qingfeng.music.agent.application;

import com.qingfeng.music.common.exception.DomainException;
import java.util.regex.Pattern;

/** Explicit replacement text is data, never another tool instruction. */
public record ExactLyricEdit(String position,String replacement) {
    private static final String LINE="(?:当前歌词的?|这首歌的?|歌词的?)?(最后|第一)(?:一)?(?:句|行)(?:歌词)?";
    private static final String REPLACE="(?:改成|改为|替换为|换成)";
    private static final String PREFIX="(?:请|麻烦)?(?:帮我)?";
    private static final Pattern REQUEST=Pattern.compile(PREFIX+"(?:只)?(?:把|将|改)?"+LINE+"[，,]?"+REPLACE+"[：:，,]?\\s*(.{1,500})",Pattern.DOTALL);
    private static final String CONTROL="(?:(?:其他|其余)(?:歌词)?(?:保持不变|不变|不动)|(?:先别|先不|暂不|不要|别)(?:生成|制作)(?:音乐|歌曲|音频|视频|MV)?)";
    private static final Pattern TRAILING_CONTROLS=Pattern.compile("(?:[，,；;。]\\s*"+CONTROL+"\\s*)+[。.!！]?$");
    private static final Pattern LEADING_CONTROLS=Pattern.compile("^(?:"+CONTROL+"\\s*[，,；;。]\\s*)+");
    private static final Pattern MISSING=Pattern.compile(PREFIX+"(?:只)?(?:(?:改|修改|改写|替换)(?:一下)?"+LINE+"|(?:把|将)?"+LINE+REPLACE+")[：:，,\\s]*(?:“\\s*”|‘\\s*’|「\\s*」|『\\s*』|\"\\s*\"|'\\s*')?[。.!！\\s]*");

    public static boolean needsReplacement(String text) {
        return text!=null&&MISSING.matcher(withoutControls(text)).matches();
    }
    private static String withoutControls(String text) {
        String instruction=LEADING_CONTROLS.matcher(text.strip()).replaceFirst("");
        return TRAILING_CONTROLS.matcher(instruction).replaceFirst("").strip();
    }
    public static ExactLyricEdit parse(String text){
        if(text==null)return null;
        String instruction=withoutControls(text);
        if(MISSING.matcher(instruction).matches())return null;
        var match=REQUEST.matcher(instruction);if(!match.matches())return null;
        String replacement=match.group(2).strip();
        if(replacement.isEmpty()||replacement.contains("\n")||replacement.contains("\r"))return null;
        String closing=switch(replacement.charAt(0)){case '“'->"”";case '‘'->"’";case '「'->"」";case '『'->"』";case '"'->"\"";case '\''->"'";default->null;};
        if(closing!=null){
            String quoted=replacement.replaceFirst("[。.!！]+$","");
            if(quoted.length()<2||!quoted.endsWith(closing))return null;
            replacement=quoted.substring(1,quoted.length()-1);
        }
        return replacement.isBlank()?null:new ExactLyricEdit(match.group(1),replacement);
    }
    public String apply(String lyrics){
        var matcher=Pattern.compile("(?m)^([^\\r\\n]+)").matcher(lyrics);int start=-1,end=-1;
        while(matcher.find()){
            String line=matcher.group().strip();
            if(line.isEmpty()||line.matches("\\[.*]|【.*】|(?:结尾)?副歌|主歌|桥段|前奏|尾奏|间奏|(?i:chorus|verse|bridge|intro|outro)"))continue;
            start=matcher.start();end=matcher.end();if(position.equals("第一"))break;
        }
        if(start<0)throw new DomainException("NO_LYRICS","这版歌词只有段落标签，请先选择有歌词正文的版本。");
        return lyrics.substring(0,start)+replacement+lyrics.substring(end);
    }
}
