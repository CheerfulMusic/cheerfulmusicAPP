package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentChatModel.Plan;
import java.util.Map;
import java.util.regex.Pattern;

/** Commands emitted on owned search cards. The executor rechecks both IDs on every open. */
public final class AgentLibraryCommand {
    private static final String ID="([a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})";
    private static final Pattern OPEN=Pattern.compile("(?:请|帮我)?打开作品\\s+"+ID+"(?:\\s+的歌词版本\\s+"+ID+")?[。！!]*");
    private static final Pattern ORDINAL=Pattern.compile("(?:请)?(?:打开|选择)第\\s*([1-9][0-9]?|[一二三四五六七八九]|十[一二三四五六七八九]?|二十)\\s*首(?:作品|歌)?[。！!]*");
    private static final Pattern SEARCH=Pattern.compile("查询我的作品，关键词[：:](.{1,200})",Pattern.DOTALL);
    private AgentLibraryCommand(){}

    public static String open(String work,String revision){
        return "打开作品 "+work+(revision==null||revision.isBlank()?"":" 的歌词版本 "+revision);
    }
    static Plan parse(String text){
        var open=OPEN.matcher(text);
        if(open.matches()){
            if(open.group(2)==null)return new Plan("select_work",Map.of("workId",open.group(1)),"");
            return new Plan("select_version",Map.of("workId",open.group(1),"revisionId",open.group(2)),"");
        }
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
        return null;
    }
}
