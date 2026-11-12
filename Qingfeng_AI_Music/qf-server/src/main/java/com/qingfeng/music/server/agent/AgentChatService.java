package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.AgentChatPolicy;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.web.app.AgentConversationController.MessageView;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** One owned, durably claimed conversational turn. Model text never executes a tool on its own. */
public final class AgentChatService {
    public record Snapshot(List<MessageView> messages, SongCard songCard, String taskId,
                           String action, String shareUrl, boolean liked) {}
    private record Outcome(String action,String shareUrl,String songId,String revisionId,String assetId) {}
    private final ConversationService conversations;
    private final AgentToolDispatcher tools;
    private final AgentChatModel model;
    private final AgentTurnStore turns;
    private final AgentSocialActions social;
    private final ObjectMapper json=new ObjectMapper();
    public AgentChatService(ConversationService conversations, AgentToolDispatcher tools, AgentChatModel model,
                            AgentTurnStore turns, AgentSocialActions social) {
        this.conversations=conversations;this.tools=tools;this.model=model;this.turns=turns;this.social=social;
    }
    public Snapshot state(String owner,String conversation) { return snapshot(owner,conversation,null); }
    public Snapshot send(String owner,String conversation,String text,String key,String locale) {
        if(text==null||text.isBlank()||text.length()>4000)throw new DomainException("INVALID_AGENT_MESSAGE","请输入 1–4000 字的消息。");
        try { UUID.fromString(key); } catch(Exception ignored) { throw new DomainException("INVALID_REQUEST_KEY","缺少有效的消息请求标识。"); }
        text=text.trim();
        // Ownership is checked before the claim and every business operation.
        List<AgentMessage> history=conversations.listMessages(owner,conversation);
        var claim=turns.claim(owner,conversation,key,text);
        if(!claim.fresh()) {
            var prior=json.readValue(claim.resultJson(),Outcome.class);
            // A stored result proves execution, not that its link is still current or unrevoked.
            return snapshot(owner,conversation,prior.action());
        }
        String action=null,shareUrl=null;
        SongCard sharedCard=null;
        try {
            SongCard current=current(owner);
            String socialAction=AgentChatPolicy.explicitSocial(text);
            AgentChatModel.Plan plan;
            boolean confirmed=false;
            if(socialAction!=null) {
                plan=new AgentChatModel.Plan(socialAction,Map.of(),"");
            } else {
                plan=AgentChatPolicy.confirmed(text)?pending(history):null;
                confirmed=plan!=null;
                if(plan==null) {
                    var state=conversations.state(owner,conversation);
                    plan=model.respond(new AgentChatModel.Context(text,locale==null?"zh-CN":locale,history(history),current,state==null?null:state.currentSongJson()));
                }
            }
            turns.assertActive(owner,conversation,key);
            String proposed=plan.action()==null?"chat":plan.action();
            if(AgentChatPolicy.lyricsOnly(text)&&"generate_song".equals(proposed))proposed="generate_lyrics";
            if((AgentChatPolicy.deniesAction(text)&&!AgentChatPolicy.lyricsOnly(text))||AgentChatPolicy.chatOnly(text)) {
                if(!"chat".equals(proposed))plan=new AgentChatModel.Plan("chat",Map.of(),"好的，我们先聊聊。你希望这首歌表达什么感受？本次不会提交创作或分享操作。");
                proposed="chat";
            }
            if("generate_lyrics".equals(proposed)&&!AgentChatPolicy.lyricsRequested(text)) {
                proposed="chat";plan=new AgentChatModel.Plan("chat",Map.of(),"可以先聊聊你的想法。需要写词时告诉我「写一首歌词」或具体的改词要求，我再开始创作。");
            }
            if("set_style".equals(proposed)&&!AgentChatPolicy.settingsRequested(text)) {
                proposed="chat";plan=new AgentChatModel.Plan("chat",Map.of(),"你可以告诉我想调整的曲风、人声、速度或配器。我会在明确要求后保存创作设置。");
            }
            if(AgentChatPolicy.paidMusicAction(proposed)&&!confirmed&&!AgentChatPolicy.musicRequested(text)) {
                Map<String,Object> content=new LinkedHashMap<>();
                content.put("text","创作方向已准备好。生成音频会使用创作额度；回复「确认生成」后再开始。" );
                var pendingArgs=new LinkedHashMap<String,Object>(plan.args());pendingArgs.putIfAbsent("text",text);
                content.put("pendingAction",proposed);content.put("pendingArgs",pendingArgs);
                append(owner,conversation,content);
            } else if(socialAction!=null && social!=null) {
                var result=social.execute(owner,conversation,socialAction,text,key,current);
                turns.assertActive(owner,conversation,key);
                action=result.action();shareUrl=result.shareUrl();
                if(shareUrl!=null)sharedCard=current;
                var content=new LinkedHashMap<String,Object>();content.put("text",result.reply());content.put("action",action);content.put("shareUrl",shareUrl);content.put("songId",current==null?null:current.songId());
                content.put("revisionId",current==null?null:current.revisionId());content.put("assetId",current==null?null:current.assetId());
                append(owner,conversation,content);
            } else if(AgentTool.fromId(proposed)!=null) {
                var tool=AgentTool.fromId(proposed);
                var result=tools.dispatch(tool,arguments(tool,plan.args(),text,current),owner,key);
                turns.assertActive(owner,conversation,key);
                if(ToolResult.BUSY.equals(result.status())) {
                    append(owner,conversation,Map.of("text",result.progress()==null?"已有任务正在生成，请完成后再试。":result.progress()));
                } else if(!ToolResult.OK.equals(result.status())) {
                    append(owner,conversation,Map.of("text","这次操作没有完成，请检查当前作品后重试。"));
                } else {
                    action=proposed;append(owner,conversation,Map.of("text",completedReply(tool)));
                }
            } else {
                // No social action is inferred from a model suggestion alone.
                String reply=proposed.equals("chat")?plan.reply():"请明确告诉我要对当前作品执行什么操作，例如「分享这首歌」。";
                append(owner,conversation,Map.of("text",reply));
            }
        } catch(DomainException error) {
            turns.assertActive(owner,conversation,key);
            append(owner,conversation,Map.of("text",error.getMessage(),"errorCode",error.code()));
        } catch(RuntimeException error) {
            turns.assertActive(owner,conversation,key);
            // Do not repeat a potentially submitted operation after an uncertain transport/tool outcome.
            append(owner,conversation,Map.of("text","本次处理未能完整返回。请查看当前作品和任务状态，避免重复提交生成。","errorCode","AGENT_TURN_FAILED"));
        }
        var finalCard=current(owner);
        var outcomeCard=sharedCard==null?finalCard:sharedCard;
        turns.complete(owner,conversation,key,json.writeValueAsString(new Outcome(action,shareUrl,outcomeCard==null?null:outcomeCard.songId(),outcomeCard==null?null:outcomeCard.revisionId(),outcomeCard==null?null:outcomeCard.assetId())));
        return snapshot(owner,conversation,action);
    }
    private void append(String owner,String conversation,Map<String,Object> content) {
        conversations.appendMessage(owner,conversation,"assistant",AgentMessage.KIND_TEXT,AgentMessage.STATE_DONE,json.writeValueAsString(content),null);
    }
    private SongCard current(String owner) {
        var card=tools.dispatch(AgentTool.GET_CURRENT_SONG,new AgentTool.GetCurrentSongArgs(),owner,UUID.randomUUID().toString()).songCard();
        return card==null||card.songId()==null||card.songId().isBlank()?null:card;
    }
    private Snapshot snapshot(String owner,String conversation,String action) {
        var messages=conversations.listMessages(owner,conversation).stream().map(message->new MessageView(message.id(),message.conversationId(),message.seq(),message.role(),message.kind(),message.state(),message.contentJson(),message.taskId(),message.createdAt()==null?"":message.createdAt().toString())).toList();
        SongCard card=current(owner);
        String shareUrl=null;
        if(card!=null&&card.revisionId()!=null&&card.assetId()!=null) {
            for(int i=messages.size()-1;i>=0;i--) {
                if(!"assistant".equals(messages.get(i).role()))continue;
                var content=json.readTree(messages.get(i).contentJson());
                if(!Objects.equals(card.songId(),content.path("songId").asText(null)))continue;
                if("revoke_share".equals(content.path("action").asText()))break;
                if(!"share".equals(content.path("action").asText())
                        ||!Objects.equals(card.revisionId(),content.path("revisionId").asText(null))
                        ||!Objects.equals(card.assetId(),content.path("assetId").asText(null)))continue;
                String stored=content.path("shareUrl").asText("");
                if(!stored.isBlank()){shareUrl=stored;break;}
            }
        }
        return new Snapshot(messages,card,card==null?null:card.taskId(),action,shareUrl,social!=null&&card!=null&&social.liked(owner,card));
    }
    private List<AgentChatModel.Turn> history(List<AgentMessage> messages) {
        List<AgentChatModel.Turn> history=new ArrayList<>();
        for(var message:messages) {
            if(!AgentMessage.KIND_TEXT.equals(message.kind()))continue;
            String text=json.readTree(message.contentJson()).path("text").asText("");
            if(!text.isBlank())history.add(new AgentChatModel.Turn(message.role(),text));
        }
        return history.subList(Math.max(0,history.size()-20),history.size());
    }
    private AgentChatModel.Plan pending(List<AgentMessage> history) {
        for(int i=history.size()-1;i>=0;i--) {
            var message=history.get(i);
            if(!"assistant".equals(message.role())||!AgentMessage.KIND_TEXT.equals(message.kind()))continue;
            var body=json.readTree(message.contentJson());String action=body.path("pendingAction").asText("");
            if(!AgentChatPolicy.paidMusicAction(action))return null;
            Map<String,Object> args=body.path("pendingArgs").isObject()?json.convertValue(body.path("pendingArgs"),Map.class):Map.of();
            return new AgentChatModel.Plan(action,args,"");
        }
        return null;
    }
    private Object arguments(AgentTool tool,Map<String,Object> args,String text,SongCard current) {
        String instructions=string(args,"text");if(instructions==null||instructions.isBlank())instructions=text;
        String language=string(args,"language");if(language!=null&&language.isBlank())language=null;
        String revision=string(args,"revisionId");
        if((revision==null||revision.isBlank())&&current!=null)revision=current.revisionId();
        String mode=string(args,"mode");
        return switch(tool) {
            case GENERATE_SONG -> new AgentTool.GenerateSongArgs(instructions,string(args,"style"),language,integer(args,"bpm"),string(args,"vocal"),strings(args,"instruments"),mode==null?(current==null?"new":"regenerate"):mode,revision);
            case GENERATE_LYRICS -> new AgentTool.GenerateLyricsArgs(instructions,language,mode==null?(current==null?"new":"rewrite"):mode,revision);
            case SET_STYLE -> new AgentTool.SetStyleArgs(string(args,"style"),string(args,"mood"),integer(args,"bpm"),language,strings(args,"instruments"),string(args,"vocal"));
            case MODIFY_SECTION -> new AgentTool.ModifySectionArgs(string(args,"section"),string(args,"replacementLyrics"),decimal(args,"startS"),decimal(args,"endS"));
            case EXTEND_SONG -> new AgentTool.ExtendSongArgs(string(args,"direction"),integer(args,"seconds"));
            case GET_CURRENT_SONG -> new AgentTool.GetCurrentSongArgs();
        };
    }
    private static String string(Map<String,Object> args,String key){Object v=args.get(key);return v==null?null:v.toString();}
    private static Integer integer(Map<String,Object> args,String key){String v=string(args,key);return v==null||v.isBlank()?null:Integer.valueOf(v);}
    private static Double decimal(Map<String,Object> args,String key){String v=string(args,key);return v==null||v.isBlank()?null:Double.valueOf(v);}
    private static List<String> strings(Map<String,Object> args,String key){Object v=args.get(key);return v instanceof List<?> values?values.stream().map(Object::toString).toList():null;}
    private static String completedReply(AgentTool tool){return switch(tool){
        case GENERATE_SONG -> "歌曲生成任务已提交，完成后会在这里显示真实音频。";
        case GENERATE_LYRICS -> "歌词已保存。你可以继续改词，或说「生成音乐」开始制作歌曲。";
        case SET_STYLE -> "创作设置已保存，下次生成会使用这些设置。";
        case MODIFY_SECTION -> "片段修改任务已提交，请等待处理结果。";
        case EXTEND_SONG -> "已提交更长的新版本生成任务；这会重新生成整首歌曲。";
        case GET_CURRENT_SONG -> "这是当前作品的最新状态。";
    };}
}
