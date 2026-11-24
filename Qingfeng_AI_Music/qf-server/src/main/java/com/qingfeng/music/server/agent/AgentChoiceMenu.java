package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentApplicationActions.Command;
import com.qingfeng.music.agent.api.AgentChatModel.Plan;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** A selection authorizes only the operation named by its visible, server-defined label. */
final class AgentChoiceMenu {
    private static final ObjectMapper JSON = new ObjectMapper();
    private enum Choice {
        LYRICS("generate_lyrics","直接开始生成歌词","Generate lyrics"),
        DRAFT("create_work","新建作品草稿","Create a draft"),
        DISCUSS("discuss","继续聊聊细节","Discuss the details"),
        WORKS("list_works","查看我的作品","Show my songs");

        final String id, zh, en;
        Choice(String id,String zh,String en){this.id=id;this.zh=zh;this.en=en;}
        static Choice byId(Object id){
            for(var value:values()) if(value.id.equals(id)) return value;
            return null;
        }
        static Choice byLabel(String label){
            for(var value:values()) if(value.zh.equals(label)||value.en.equalsIgnoreCase(label)) return value;
            return switch(label){
                case "生成歌词","开始生成歌词" -> LYRICS;
                case "先建一个作品草稿","新建空白作品草稿" -> DRAFT;
                default -> null;
            };
        }
    }
    private record Menu(String brief,List<Choice> choices){}
    record Selection(String intent,Plan plan){}
    private static final Pattern ROW=Pattern.compile("(?m)^\\h*(\\d{1,2})[.、)）]\\h*([^\\r\\n]+)");
    private static final Pattern NUMBER=Pattern.compile("^(?:选|选择|第)?([1-9][0-9]?)(?:项|个)?$");
    static boolean numberedInput(String input){return input.startsWith("#choice:")||NUMBER.matcher(input).matches();}
    static void validateDisplayedMenu(String input,List<AgentMessage> history,String displayedId){
        String latestId=null,menuId=null;
        for(int i=history.size()-1;i>=0;i--){
            var row=history.get(i);
            if(!AgentMessage.KIND_TEXT.equals(row.kind()))continue;
            if(!"assistant".equals(row.role())||!AgentMessage.STATE_DONE.equals(row.state()))break;
            var body=JSON.readTree(row.contentJson());
            if("progress".equals(body.path("kind").asText()))continue;
            latestId=row.id();menuId=body.path("menuId").asText(null);break;
        }
        if(!Objects.equals(latestId,displayedId)||input.startsWith("#choice:")&&(menuId==null||!input.startsWith("#choice:"+menuId+":")))
            throw new DomainException("AGENT_PROMPT_CONFLICT","选项已更新，请核对最新菜单后再选择。");
    }
    private static Menu legacy(String text){
        var rows=ROW.matcher(text);
        var choices=new ArrayList<Choice>();String brief="";
        while(rows.find()){
            if(choices.isEmpty()) brief=text.substring(0,rows.start()).strip();
            if(Integer.parseInt(rows.group(1))!=choices.size()+1)return null;
            String label=rows.group(2).split("——| — | - ",2)[0].replace("**","").strip();
            var choice=Choice.byLabel(label);
            if(choice==null||choices.contains(choice))return null;
            choices.add(choice);
        }
        return choices.size()<2?null:new Menu(brief,choices);
    }
    static Map<String,Object> reply(Plan plan,String text,String locale){
        var choices=new ArrayList<Choice>();
        if(plan.args().get("choices") instanceof List<?> ids && ids.size()>=2 && ids.size()<=Choice.values().length){
            for(var id:ids){
                var choice=Choice.byId(id);
                if(choice==null||choices.contains(choice)){choices.clear();break;}
                choices.add(choice);
            }
        }
        var menu=legacy(text);
        if(menu==null && choices.size()>=2 && !ROW.matcher(text).find())menu=new Menu(text,choices);
        if(menu==null)return Map.of("text",text);
        boolean english=locale!=null&&locale.startsWith("en");
        var display=new StringBuilder(menu.brief());
        var commands=new ArrayList<Command>();
        String menuId=UUID.randomUUID().toString();
        for(var choice:menu.choices()){
            int number=commands.size()+1;String label=english?choice.en:choice.zh;
            display.append("\n").append(number).append(". ").append(label);
            commands.add(new Command(number+". "+label,"#choice:"+menuId+":"+number,"send"));
        }
        return Map.of("text",display.toString(),"kind","choice_menu","menuId",menuId,"brief",menu.brief(),
                "choices",menu.choices().stream().map(c->c.id).toList(),"commands",commands);
    }
    static Selection select(String input,List<AgentMessage> history,String locale){
        boolean button=input.startsWith("#choice:");
        var number=NUMBER.matcher(input);
        boolean numeric=number.matches();
        boolean direct=Choice.byLabel(input)==Choice.LYRICS;
        if(!button&&!numeric&&!direct)return null;
        Menu menu=null;String menuId="";int failedAttempts=0;
        for(int i=history.size()-1;i>=0;i--){
            var m=history.get(i);
            if(!"assistant".equals(m.role()))break;
            var body=JSON.readTree(m.contentJson());
            if("progress".equals(body.path("kind").asText()))continue;
            boolean refused="AGENT_INTENT_UNRESOLVED".equals(body.path("errorCode").asText())
                    || body.path("text").asText("").startsWith("还没有执行「");
            if(direct && refused && failedAttempts<2){
                int user=i-1;
                while(user>=0 && "assistant".equals(history.get(user).role())
                        && "progress".equals(JSON.readTree(history.get(user).contentJson()).path("kind").asText()))user--;
                if(user<0 || !"user".equals(history.get(user).role()))break;
                String attempt=JSON.readTree(history.get(user).contentJson()).path("text").asText("");
                if(!attempt.matches("[1-9]") && Choice.byLabel(attempt)!=Choice.LYRICS)break;
                i=user;
                failedAttempts++;
                continue;
            }
            if("choice_menu".equals(body.path("kind").asText())){
                var options=new ArrayList<Choice>();
                for(var id:body.path("choices")){var c=Choice.byId(id.asText());if(c==null)return clarify(locale);options.add(c);}
                menu=new Menu(body.path("brief").asText(""),options);menuId=body.path("menuId").asText("");
            }else if(!body.has("kind")&&!body.has("errorCode"))menu=legacy(body.path("text").asText(""));
            // Plain numeric answers (e.g. a tempo) and existing work selectors keep their normal route.
            if(numeric && menu==null && !ROW.matcher(body.path("text").asText("")).find())return null;
            break;
        }
        Choice choice=Choice.LYRICS;
        if(button||numeric){
            if(menu==null)return clarify(locale);
            int index;
            if(button){
                String prefix="#choice:"+menuId+":";
                if(menuId.isEmpty()||!input.startsWith(prefix)||!input.substring(prefix.length()).matches("[1-9]"))return clarify(locale);
                index=Integer.parseInt(input.substring(prefix.length()));
            }else index=Integer.parseInt(number.group(1));
            if(index>menu.choices().size())return clarify(locale);
            choice=menu.choices().get(index-1);
        }
        if(direct&&(menu==null||!menu.choices().contains(Choice.LYRICS)))return null;
        String brief=menu.brief();
        if(brief.length()>3000)brief=brief.substring(0,3000);
        boolean en=locale!=null&&locale.startsWith("en");
        String intent=en?choice.en:choice.zh;
        if(choice==Choice.DISCUSS)return new Selection(intent,new Plan("chat",Map.of(),en?"What emotion or image would you like in the chorus?":"你希望副歌表达什么情绪，或出现哪个画面？"));
        Map<String,Object> args=switch(choice){
            case LYRICS -> Map.of("text",brief.isBlank()?intent:brief,"mode","new");
            case DRAFT -> Map.of("story",brief);
            default -> Map.of();
        };
        // Use the canonical instruction accepted by the policy, independently of UI language.
        return new Selection(choice.zh,new Plan(choice.id,args,""));
    }
    private static Selection clarify(String locale){
        String reply=locale!=null&&locale.startsWith("en")?"Please name the option you want to use.":"请发一下要选的操作名称，我无法把这个编号对应到当前选项。";
        return new Selection("",new Plan("chat",Map.of(),reply));
    }
}
