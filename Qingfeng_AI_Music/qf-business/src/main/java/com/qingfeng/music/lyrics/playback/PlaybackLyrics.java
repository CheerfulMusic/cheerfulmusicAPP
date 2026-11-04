package com.qingfeng.music.lyrics.playback;

import java.util.*;
import java.util.regex.Pattern;

/** 播放时间轴与已归档音频绑定，不随可编辑歌词版本改变。所有时间均来自供应商。 */
public final class PlaybackLyrics {
    private PlaybackLyrics() {}
    public record Word(String text,double start,double end) {}
    public record Line(String text,double start,double end) {}
    public record Snapshot(String status,String text,List<Line> lines,boolean canSync) {}
    private static final Pattern SECTION=Pattern.compile("\\[(?:verse(?:\\s*\\d+)?|chorus(?:\\s*\\d+)?|pre[- ]?chorus|bridge|intro|outro|instrumental|interlude|hook|主歌\\s*\\d*|副歌\\s*\\d*|桥段|前奏|尾奏|间奏)\\]",Pattern.CASE_INSENSITIVE);

    /** 依据换行、标点和单词边界分组；绝不按歌曲时长平均分配时间。 */
    public static List<Line> lines(List<Word> words) {
        if(words==null)return List.of();
        var valid=words.stream().filter(w->w!=null&&w.text()!=null&&w.text().length()<=12000&&Double.isFinite(w.start())&&Double.isFinite(w.end())&&w.start()>=0&&w.end()>w.start()&&w.end()<=86400).sorted(Comparator.comparingDouble(Word::start)).limit(20000).toList();
        var result=new ArrayList<Line>();var text=new StringBuilder();double start=0,end=0;
        for(var word:valid){
            String raw=SECTION.matcher(word.text()).replaceAll("").replace("\r\n","\n").replace('\r','\n');
            String clean=raw.trim().replaceAll("\\s*\\n+\\s*"," / ");
            if(clean.isBlank())continue;
            boolean leadingNewline=raw.indexOf('\n')>=0&&raw.substring(0,raw.indexOf('\n')).isBlank();
            int max=clean.matches("(?s).*[\\p{IsHan}].*")?40:90;
            if(text.length()>0&&(leadingNewline||text.length()+clean.length()>max)) {result.add(new Line(text.toString().trim(),start,end));text.setLength(0);}
            if(text.length()==0){start=word.start();end=word.end();}
            else{end=Math.max(end,word.end());if(Character.isLetterOrDigit(text.charAt(text.length()-1))&&text.charAt(text.length()-1)<128&&Character.isLetterOrDigit(clean.charAt(0))&&clean.charAt(0)<128)text.append(' ');}
            text.append(clean);
            if((raw.stripTrailing().length()<raw.length()&&raw.substring(raw.stripTrailing().length()).contains("\n"))||clean.matches("(?s).*[。！？!?;；]$")) {
                result.add(new Line(text.toString().trim(),start,end));text.setLength(0);
            }
        }
        if(text.length()>0)result.add(new Line(text.toString().trim(),start,end));
        return List.copyOf(result);
    }
}
