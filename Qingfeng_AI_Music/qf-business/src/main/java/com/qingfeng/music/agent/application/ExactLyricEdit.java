package com.qingfeng.music.agent.application;

import java.util.regex.Pattern;

/** Explicit replacement text is data, never another tool instruction. */
public record ExactLyricEdit(String position,String replacement) {
    private static final Pattern REQUEST=Pattern.compile("^(?:请|麻烦)?(?:帮我)?(?:把|将)?(?:当前歌词的?|这首歌的?|歌词的?)?(最后|第一)(?:一)?(?:句|行)(?:歌词)?(?:改成|改为|替换为|换成)[：:，,]?\\s*(.{1,500})$",Pattern.DOTALL);
    public static ExactLyricEdit parse(String text){
        if(text==null)return null;
        var match=REQUEST.matcher(text.strip());if(!match.matches())return null;
        String replacement=match.group(2).strip().replaceFirst("[，,；;。]\\s*(?:其他|其余)(?:歌词)?(?:保持不变|不变|不动)[。！!]?\\s*$","").strip();
        if(replacement.isEmpty()||replacement.contains("\n")||replacement.contains("\r"))return null;
        if((replacement.startsWith("“")&&replacement.endsWith("”"))||(replacement.startsWith("‘")&&replacement.endsWith("’"))||(replacement.startsWith("\"")&&replacement.endsWith("\""))||(replacement.startsWith("'")&&replacement.endsWith("'")))replacement=replacement.substring(1,replacement.length()-1);
        return replacement.isBlank()?null:new ExactLyricEdit(match.group(1),replacement);
    }
    public String apply(String lyrics){
        var matcher=Pattern.compile("(?m)^([^\\r\\n]+)").matcher(lyrics);int start=-1,end=-1;
        while(matcher.find()){
            String line=matcher.group().strip();
            if(line.isEmpty()||line.matches("\\[.*]|【.*】|(?:结尾)?副歌|主歌|桥段|前奏|尾奏|间奏|(?i:chorus|verse|bridge|intro|outro)"))continue;
            start=matcher.start();end=matcher.end();if(position.equals("第一"))break;
        }
        if(start<0)throw new IllegalArgumentException("没有可替换的歌词行");
        return lyrics.substring(0,start)+replacement+lyrics.substring(end);
    }
}
