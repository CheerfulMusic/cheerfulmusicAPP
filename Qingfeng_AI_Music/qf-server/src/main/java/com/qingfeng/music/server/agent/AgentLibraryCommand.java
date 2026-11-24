package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentChatModel.Plan;
import java.util.Map;
import java.util.regex.Pattern;

/** Commands emitted on owned search cards. The executor rechecks both IDs on every open. */
public final class AgentLibraryCommand {
    private static final String ID="([a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})";
    private static final Pattern OPEN=Pattern.compile("(?:请|帮我)?(?:打开|选择)作品\\s+"+ID+"(?:\\s+的歌词版本\\s+"+ID+")?[。！!]*");
    private static final Pattern MEDIA=Pattern.compile("选择作品\\s+"+ID+"\\s+的(音频|封面)\\s+"+ID);
    private static final Pattern SYNC=Pattern.compile("同步作品\\s+"+ID+"\\s+音频\\s+"+ID+"\\s+的歌词时间轴");
    private static final Pattern ORDINAL=Pattern.compile("(?:请)?(?:打开|选择)第\\s*([1-9][0-9]?|[一二三四五六七八九]|十[一二三四五六七八九]?|二十)\\s*首(?:作品|歌)?[。！!]*");
    private static final Pattern SEARCH=Pattern.compile("查询我的作品，关键词[：:](.{1,200})",Pattern.DOTALL);
    private static final Pattern PAGE=Pattern.compile("列出我的作品，第 ([1-9][0-9]{0,4}) 页（page=([0-9]{1,5})）(?:，关键词[：:](.{1,200}))?",Pattern.DOTALL);
    private AgentLibraryCommand(){}

    public static String open(String work,String revision){
        return "打开作品 "+work+(revision==null||revision.isBlank()?"":" 的歌词版本 "+revision);
    }
    static String page(int index,String query){
        return "列出我的作品，第 "+(index+1)+" 页（page="+index+"）"+(query.isBlank()?"":"，关键词："+query);
    }
    static Plan select(String work,String revision){
        return revision==null||revision.isBlank()?new Plan("select_work",Map.of("workId",work),"")
            :new Plan("select_version",Map.of("workId",work,"revisionId",revision),"");
    }
    static Plan parse(String text){
        var open=OPEN.matcher(text);
        if(open.matches())return select(open.group(1),open.group(2));
        var media=MEDIA.matcher(text);
        if(media.matches()){
            boolean audio="音频".equals(media.group(2));
            return new Plan(audio?"select_audio":"select_cover",Map.of("workId",media.group(1),audio?"assetId":"coverId",media.group(3)),"");
        }
        var sync=SYNC.matcher(text);
        if(sync.matches())return new Plan("sync_playback_lyrics",Map.of("workId",sync.group(1),"assetId",sync.group(2)),"");
        var ordinal=ORDINAL.matcher(text);
        if(ordinal.matches()){
            String n=ordinal.group(1);
            int index;
            if(Character.isDigit(n.charAt(0)))index=Integer.parseInt(n);
            else if(n.equals("二十"))index=20;
            else if(n.startsWith("十"))index=n.length()==1?10:11+"一二三四五六七八九".indexOf(n.charAt(1));
            else index="一二三四五六七八九".indexOf(n)+1;
            return new Plan("select_work",Map.of("index",index),"");
        }
        var search=SEARCH.matcher(text);
        if(search.matches()&&!search.group(1).isBlank())return new Plan("list_works",Map.of("query",search.group(1).strip()),"");
        var page=PAGE.matcher(text);
        if(page.matches()){
            int index=Integer.parseInt(page.group(2));
            if(index<=10000&&Integer.parseInt(page.group(1))==index+1)
                return new Plan("list_works",Map.of("page",index,"query",page.group(3)==null?"":page.group(3)),"");
        }
        return null;
    }
}
