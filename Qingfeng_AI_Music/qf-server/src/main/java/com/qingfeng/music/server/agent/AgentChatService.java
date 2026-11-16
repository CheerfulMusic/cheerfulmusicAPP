package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.api.AgentApplicationActions.*;
import com.qingfeng.music.agent.api.AgentChatModel.Step;
import com.qingfeng.music.agent.application.AgentChatPolicy;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.web.app.AgentConversationController.MessageView;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Bounded durable plans: model proposals never grant execution authority. */
public final class AgentChatService {
    public record Continuation(String requestKey,String text) {}
    public record Snapshot(List<MessageView> messages, SongCard songCard, String taskId,
                           String action, String shareUrl, boolean liked, Continuation continuation,
                           String messagesMode,boolean hasOlderMessages,long oldestSeq,long latestSeq,long historyEpoch,boolean hasNewerMessages) {
        public Snapshot(List<MessageView> messages,SongCard songCard,String taskId,String action,String shareUrl,boolean liked,Continuation continuation){this(messages,songCard,taskId,action,shareUrl,liked,continuation,"replace",false,0,0,0,false);}
    }
    private record Outcome(String action,String shareUrl,String songId,String revisionId,String assetId) {}
    private record Pending(List<Step> steps,String authorizedText,String songId,String revisionId,
                           String taskId,String continuationKey,Instant createdAt,boolean workResolved,boolean versionResolved) {
        boolean automatic(){return continuationKey!=null;}
    }
    private static final String CONTINUE="继续已安排的操作";
    private static final Set<String> SOCIAL=Set.of("share","revoke_share","like","unlike","play","download","cancel");
    private static final Set<String> CLIENT_MEDIA=Set.of("play","download");
    private static final Set<String> ACTIVE=Set.of("QUEUED","SUBMITTING","RUNNING","SAVING","SUBMISSION_UNKNOWN");
    private final ConversationService conversations;
    private final AgentToolDispatcher tools;
    private final AgentChatModel model;
    private final AgentTurnStore turns;
    private final AgentSocialActions social;
    private final List<AgentApplicationActions> applications;
    private final ObjectMapper json=new ObjectMapper();
    private boolean progressEnabled;
    /** Observable execution uses the same durable turn; it never starts another model/tool loop. */
    public AgentChatService withProgress(boolean enabled){this.progressEnabled=enabled;return this;}
    public AgentChatService(ConversationService c,AgentToolDispatcher t,AgentChatModel m,AgentTurnStore ts,AgentSocialActions s){this(c,t,m,ts,s,List.of());}
    public AgentChatService(ConversationService c,AgentToolDispatcher t,AgentChatModel m,AgentTurnStore ts,AgentSocialActions s,List<AgentApplicationActions> apps){
        conversations=c;tools=t;model=m;turns=ts;social=s;applications=List.copyOf(apps);
        var ids=new HashSet<String>();
        for(var capability:capabilities())if(!ids.add(capability.id()))throw new IllegalArgumentException("Duplicate agent capability: "+capability.id());
    }
    public Snapshot state(String owner,String conversation){return snapshot(owner,conversation,null);}
    public Snapshot state(String owner,String conversation,Long afterSeq,Long historyEpoch){return snapshot(owner,conversation,null,null,afterSeq,historyEpoch);}
    public Snapshot send(String owner,String conversation,String text,String key,String locale) {
        if(text==null||text.isBlank()||text.length()>4000)throw new DomainException("INVALID_AGENT_MESSAGE","请输入 1–4000 字的消息。");
        try{UUID.fromString(key);}catch(Exception ignored){throw new DomainException("INVALID_REQUEST_KEY","缺少有效的消息请求标识。");}
        text=text.trim();
        AgentRequestScope.check();
        var history=conversations.listRecentMessages(owner,conversation,80);
        var claim=turns.claim(owner,conversation,key,text);
        if(!claim.fresh()) {
            var previous=json.readValue(claim.resultJson(),Outcome.class);
            return snapshot(owner,conversation,previous.action(),previous);
        }
        String action=null,shareUrl=null,clientAction=null;SongCard sharedCard=null,clientCard=null;
        try {
            SongCard current=current(owner);
            Pending pending=pending(history);
            boolean confirmation=AgentChatPolicy.confirmed(text)&&pending!=null&&!pending.automatic();
            boolean continuation=CONTINUE.equals(text)&&pending!=null&&pending.automatic();
            String authorizedText=text;
            AgentChatModel.Plan plan;
            int modelCalls=0;
            if(pending!=null&&Set.of("取消操作","取消确认","算了","不用了","停止后续操作").contains(text)) {
                plan=new AgentChatModel.Plan("chat",Map.of(),"已停止后续操作。已经完成的操作和已提交的生成任务会保留。");
            } else if(confirmation||continuation) {
                if(!pendingValid(pending,current,continuation)||continuation&&!key.equals(pending.continuationKey()))
                    throw new DomainException("AGENT_PLAN_STALE","作品或任务状态已变化，之前的操作计划已停止。请重新说明要执行的操作。");
                authorizedText=pending.authorizedText();
                plan=new AgentChatModel.Plan(null,Map.of(),"",pending.steps());
            } else {
                var exact=com.qingfeng.music.agent.application.ExactLyricEdit.parse(text);
                String fast=AgentChatPolicy.currentSocial(text);
                if(exact!=null)plan=new AgentChatModel.Plan("replace_lyric_line",Map.of(),"");
                else if(fast!=null)plan=new AgentChatModel.Plan(fast,Map.of(),"");
                else if(text.matches("(?:查看|列出|显示)?(?:全部|所有|可用)(?:功能|能力)|能力清单"))plan=new AgentChatModel.Plan("list_capabilities",Map.of(),"");
                else {
                    var state=conversations.state(owner,conversation);
                    progress(owner,conversation,key,"planning",null,0,"正在理解你的要求。");
                    plan=model.respond(new AgentChatModel.Context(text,locale==null?"zh-CN":locale,history(history),current,
                        state==null?null:state.currentSongJson(),capabilities(),results(history)).withExecution(new AgentChatModel.Execution(owner,conversation,key)));
                    modelCalls++;
                }
            }
            List<Step> steps=normalizedSteps(plan.orderedSteps(),authorizedText);validatePlan(steps);
            final String originalIntent=authorizedText;
            String writtenLyricsWork=null;
            // Confirmations/continuations already carry a server-frozen object binding.
            boolean resolvedWork=(confirmation||continuation)&&pending.workResolved(),resolvedVersion=(confirmation||continuation)&&pending.versionResolved();
            SongCard resolvedTarget=resolvedWork||resolvedVersion?current:null;
            for(int i=0;i<steps.size();i++) {
                AgentRequestScope.check();
                turns.assertActive(owner,conversation,key);
                current=current(owner);
                Step step=steps.get(i);String proposed=step.action();
                Map<String,Object> args=new LinkedHashMap<>(step.args());
                boolean explicitlyConfirmed=confirmation&&i==0;
                boolean requested=AgentChatPolicy.actionRequested(proposed,authorizedText);
                if("chat".equals(proposed)){append(owner,conversation,Map.of("text",safeReply(plan.reply())));break;}
                var capability=capability(proposed);
                if(!requested&&!explicitlyConfirmed&&!"read".equals(capability.effect())) {
                    if(AgentChatPolicy.paidMusicAction(proposed)&&!AgentChatPolicy.chatOnly(authorizedText)&&!AgentChatPolicy.deniesAction(authorizedText)){
                        args.putIfAbsent("text",authorizedText);
                        ask(owner,conversation,replaceFirst(steps.subList(i,steps.size()),proposed,args),authorizedText,current,
                             "创作方向已准备好。生成音频会使用创作额度；确认后开始。",null,null,resolvedWork,resolvedVersion);
                    } else append(owner,conversation,Map.of("text","我还没有执行这项操作。请明确告诉我需要处理的作品和动作；也可以继续讨论想法。"));
                    break;
                }
                if(!explicitlyConfirmed&&!"replace_lyric_line".equals(proposed)&&AgentChatPolicy.chatOnly(authorizedText)&&!"read".equals(capability.effect())){
                    append(owner,conversation,Map.of("text","我们可以先讨论这个功能，需要执行时再告诉我具体要求。"));break;
                }
                AgentApplicationActions executor=executor(proposed);
                // A confirmation is tied to the exact object/version that was shown. Preparing again
                // would silently replace expectedVersion and authorize deleting changed content.
                if(executor!=null&&!explicitlyConfirmed)args=new LinkedHashMap<>(executor.prepare(owner,conversation,proposed,args,current));
                if(("destructive".equals(capability.effect())||AgentChatPolicy.requiresConfirmation(proposed))&&!explicitlyConfirmed){
                    String target=String.valueOf(args.getOrDefault("title",current==null?"当前账号":current.title()));
                    if(args.get("workId")!=null)target+="（作品 "+args.get("workId")+"，版本 "+args.getOrDefault("expectedVersion","当前")+"）";
                    ask(owner,conversation,replaceFirst(steps.subList(i,steps.size()),proposed,args),authorizedText,current,
                         "请确认："+capability.description()+"。目标："+target+"。",null,null,resolvedWork,resolvedVersion);break;
                }
                if(SOCIAL.contains(proposed)&&needsResolvedSocialTarget(proposed,authorizedText)
                        &&(!sameTarget(resolvedTarget,current)||versionReference(authorizedText)&&!resolvedVersion
                            ||requiresWorkResolution(authorizedText)&&!resolvedWork)) {
                    append(owner,conversation,Map.of("text","还没有确认你指定的那首作品或版本，本次没有执行这项操作。请先从作品或版本列表选择目标，再继续。"));break;
                }
                String stepKey=steps.size()==1?key:derivedKey(key+":"+i+":"+proposed);
                progress(owner,conversation,key,"tool_started",proposed,i+1,"正在处理："+capability.description());
                if("list_capabilities".equals(proposed)){
                    var items=capabilities().stream().map(c->new Item(c.id(),c.description(),c.effect().equals("native")?"在对话内打开专用操作窗口":"可通过对话执行",Map.of())).toList();
                    progress(owner,conversation,key,"tool_finished",proposed,i+1,"已取得功能列表。");
                    appendResult(owner,conversation,new Result("以下是已接入的功能。社区公开发布、平台发行和无缝音频延长尚未提供。","capabilities",items,List.of(),proposed));action=proposed;
                } else if(SOCIAL.contains(proposed)&&social!=null) {
                    AgentRequestScope.check();
                    var result=social.execute(owner,conversation,proposed,authorizedText,stepKey,current);
                    turns.assertActive(owner,conversation,key);action=result.action();shareUrl=result.shareUrl();if(shareUrl!=null)sharedCard=current;
                    if(action!=null&&CLIENT_MEDIA.contains(action)){clientAction=action;clientCard=current;}
                    var body=new LinkedHashMap<String,Object>();body.put("text",result.reply());body.put("action",action);body.put("shareUrl",shareUrl);
                    progress(owner,conversation,key,"tool_finished",proposed,i+1,"本步骤已返回结果，请查看具体说明。");
                    body.put("songId",current==null?null:current.songId());body.put("revisionId",current==null?null:current.revisionId());body.put("assetId",current==null?null:current.assetId());append(owner,conversation,body);
                    if(action==null)break;
                } else if(executor!=null) {
                    AgentRequestScope.check();
                    var result=executor.execute(owner,conversation,proposed,args,authorizedText,stepKey,current);
                    turns.assertActive(owner,conversation,key);
                    progress(owner,conversation,key,"tool_finished",proposed,i+1,"本步骤已返回结果，请查看具体说明。");
                    appendResult(owner,conversation,result);action=result.action();
                    if(action==null||"native".equals(capability.effect()))break;
                } else if(AgentTool.fromId(proposed)!=null) {
                    var tool=AgentTool.fromId(proposed);
                    // Later steps use the newly committed current revision, never a plan's old version.
                    if(i>0&&(tool==AgentTool.GENERATE_SONG||tool==AgentTool.GENERATE_LYRICS))args.remove("revisionId");
                    if(tool==AgentTool.GENERATE_SONG&&current!=null&&Objects.equals(writtenLyricsWork,current.songId())) {
                        args.put("mode","regenerate");args.put("revisionId",current.revisionId());
                    }
                    AgentRequestScope.check();
                    var result=tools.dispatch(tool,arguments(tool,args,authorizedText,current),owner,stepKey);
                    turns.assertActive(owner,conversation,key);
                    if(ToolResult.BUSY.equals(result.status())){append(owner,conversation,Map.of("text",result.progress()==null?"已有任务正在生成，请完成后再试。":result.progress()));break;}
                    if(!ToolResult.OK.equals(result.status())){append(owner,conversation,Map.of("text","这一步没有完成，后续操作已停止。请检查当前作品后重试。"));break;}
                    progress(owner,conversation,key,"tool_finished",proposed,i+1,"本步骤已返回结果，请查看作品和任务状态。");
                    action=proposed;append(owner,conversation,Map.of("text",completedReply(tool)));
                } else throw new DomainException("AGENT_ACTION_UNAVAILABLE","当前环境未启用这项操作。");
                SongCard updated=current(owner);
                if(Set.of("select_version","select_audio").contains(proposed)) {
                    resolvedWork=resolvedWork&&resolvedTarget!=null&&updated!=null&&Objects.equals(resolvedTarget.songId(),updated.songId());
                    resolvedTarget=updated;resolvedVersion=true;
                } else if("select_work".equals(proposed)) {
                    resolvedTarget=updated;resolvedWork=updated!=null;resolvedVersion=false;
                } else if(Set.of("create_work","generate_lyrics","generate_song","save_lyrics").contains(proposed)&&resolvedTarget!=null) {
                    boolean sameWork=updated!=null&&Objects.equals(resolvedTarget.songId(),updated.songId());
                    resolvedWork=resolvedWork&&sameWork;
                    resolvedVersion=resolvedVersion&&sameWork&&Objects.equals(resolvedTarget.revisionId(),updated.revisionId());
                    resolvedTarget=resolvedWork||resolvedVersion?updated:null;
                }
                if(("generate_lyrics".equals(proposed)||"save_lyrics".equals(proposed))&&updated!=null)writtenLyricsWork=updated.songId();
                if(i+1<steps.size()&&"edit_cover".equals(proposed)) {
                    append(owner,conversation,Map.of("text","封面任务已提交，后续步骤需要等新封面完成后再执行。可以查询封面列表查看进度。"));break;
                }
                if(i+1<steps.size()&&updated!=null&&updated.taskId()!=null&&ACTIVE.contains(updated.status())&&AgentChatPolicy.paidMusicAction(proposed)){
                    var remaining=steps.subList(i+1,steps.size());
                    if(remaining.stream().anyMatch(s->!AgentChatPolicy.actionRequested(s.action(),originalIntent)&&!"read".equals(capability(s.action()).effect()))){
                        append(owner,conversation,Map.of("text","生成任务已提交。后续动作还需要你明确说明，暂未安排。"));break;
                    }
                    ask(owner,conversation,remaining,authorizedText,updated,"任务正在处理，完成后会继续你已安排的后续操作。",updated.taskId(),derivedKey(key+":continue"),resolvedWork,resolvedVersion);break;
                }
                // A query can resolve the IDs needed by the original instruction ('上一版').
                // Replan once using only persisted tool results; original authorization never grows.
                if(i+1==steps.size()&&modelCalls==1&&!confirmation&&!continuation&&"read".equals(capability.effect())) {
                    AgentRequestScope.check();
                    var observed=conversations.listRecentMessages(owner,conversation,80);var state=conversations.state(owner,conversation);
                    var next=model.respond(new AgentChatModel.Context(originalIntent,locale==null?"zh-CN":locale,history(observed),updated,
                        state==null?null:state.currentSongJson(),capabilities(),results(observed)).withExecution(new AgentChatModel.Execution(owner,conversation,key)));
                    modelCalls++;
                    var follow=normalizedSteps(next.orderedSteps(),originalIntent);
                    // A planner that repeats the same query has made no progress. Do not execute it twice.
                    var completedSteps=List.copyOf(steps.subList(0,i+1));
                    if(follow.stream().anyMatch(completedSteps::contains))break;
                    var expanded=new ArrayList<>(steps);expanded.addAll(follow);validatePlan(expanded);
                    steps=expanded;plan=next;
                }
            }
        } catch(DomainException error) {
            turns.assertActive(owner,conversation,key);append(owner,conversation,Map.of("text",error.getMessage(),"errorCode",error.code()));
        } catch(RuntimeException error) {
            turns.assertActive(owner,conversation,key);append(owner,conversation,Map.of("text","本次处理未能完整返回，后续步骤已停止。请查看当前作品和任务状态，避免重复提交。","errorCode","AGENT_TURN_FAILED"));
        }
        SongCard finalCard=current(owner),outcomeCard=clientCard!=null?clientCard:sharedCard==null?finalCard:sharedCard;
        // Read-only results must not swallow an earlier browser action. Its media target is frozen,
        // and snapshot validates it against the very card returned to the browser (including retries).
        if(clientAction!=null)action=clientAction;
        var outcome=new Outcome(action,shareUrl,outcomeCard==null?null:outcomeCard.songId(),outcomeCard==null?null:outcomeCard.revisionId(),outcomeCard==null?null:outcomeCard.assetId());
        turns.complete(owner,conversation,key,json.writeValueAsString(outcome));
        return snapshot(owner,conversation,action,outcome);
    }
    private List<Step> normalizedSteps(List<Step> steps,String intent) {
        if(!AgentChatPolicy.lyricsOnly(intent))return steps;
        return steps.stream().map(s->"generate_song".equals(s.action())?new Step("generate_lyrics",s.args()):s).toList();
    }
    private void validatePlan(List<Step> steps) {
        if(steps.isEmpty()||steps.size()>6)throw new DomainException("INVALID_AGENT_PLAN","一次最多处理六个步骤，请把任务分成几次完成。");
        int audio=0,lyrics=0,clientMedia=0;var writes=new HashSet<Step>();var paidTypes=new HashSet<String>();
        for(var step:steps){
            if("chat".equals(step.action()))continue;
            var capability=capability(step.action());
            if("paid".equals(capability.effect())&&!paidTypes.add(step.action()))throw new DomainException("INVALID_AGENT_PLAN","一次请求每种付费操作最多执行一次。请先查看这次结果，再继续下一次创作。");
            if(clientMedia>0&&!"read".equals(capability.effect()))throw new DomainException("INVALID_AGENT_PLAN","一次只能播放或下载一首作品；之后只能查询信息。请先完成作品切换和修改，再单独播放或下载。");
            if(CLIENT_MEDIA.contains(step.action()))clientMedia++;
            if(!"read".equals(capability.effect())&&!writes.add(step))throw new DomainException("INVALID_AGENT_PLAN","计划包含重复操作，本次没有继续执行。请明确每一步需要处理的作品。");
            if(AgentChatPolicy.paidMusicAction(step.action()))audio++;if("generate_lyrics".equals(step.action()))lyrics++;
        }
        if(audio>1||lyrics>1)throw new DomainException("INVALID_AGENT_PLAN","一次请求最多生成一组音乐和一版歌词。请先完成当前版本，再继续创作。");
    }
    private void ask(String owner,String conversation,List<Step> steps,String authorizedText,SongCard current,String text,String taskId,String continuationKey,boolean workResolved,boolean versionResolved) {
        var body=new LinkedHashMap<String,Object>();body.put("text",text);body.put("kind",continuationKey==null?"confirmation":"waiting");
        body.put("pendingSteps",steps);body.put("authorizedText",authorizedText);body.put("pendingSongId",current==null?null:current.songId());
        body.put("pendingRevisionId",current==null?null:current.revisionId());body.put("pendingTaskId",taskId);body.put("continuationKey",continuationKey);
        body.put("pendingWorkResolved",workResolved);body.put("pendingVersionResolved",versionResolved);
        body.put("commands",continuationKey==null?List.of(new Command("确认执行","确认执行","send"),new Command("取消","取消操作","send")):List.of(new Command("停止后续操作","停止后续操作","send")));append(owner,conversation,body);
    }
    private Pending pending(List<AgentMessage> history) {
        for(int i=history.size()-1;i>=0;i--){
            var m=history.get(i);
            // A newer user message supersedes an older pending plan, including while its model call is running.
            if("user".equals(m.role())&&AgentMessage.KIND_TEXT.equals(m.kind()))return null;
            if(!"assistant".equals(m.role())||!AgentMessage.KIND_TEXT.equals(m.kind()))continue;
            var b=json.readTree(m.contentJson());if("progress".equals(b.path("kind").asText()))continue;if(!b.path("pendingSteps").isArray())return null;
            List<Step> steps=new ArrayList<>();for(var s:b.path("pendingSteps"))steps.add(new Step(s.path("action").asText(),json.convertValue(s.path("args"),Map.class)));
            return new Pending(steps,b.path("authorizedText").asText(""),b.path("pendingSongId").asText(null),b.path("pendingRevisionId").asText(null),b.path("pendingTaskId").asText(null),b.path("continuationKey").asText(null),m.createdAt(),b.path("pendingWorkResolved").asBoolean(false),b.path("pendingVersionResolved").asBoolean(false));
        }return null;
    }
    private boolean pendingValid(Pending p,SongCard current,boolean automatic) {
        if(p.createdAt()==null||Duration.between(p.createdAt(),Instant.now()).compareTo(Duration.ofHours(automatic?24:1))>0)return false;
        if(!Objects.equals(p.songId(),current==null?null:current.songId())||!Objects.equals(p.revisionId(),current==null?null:current.revisionId()))return false;
        return !automatic||current!=null&&Objects.equals(p.taskId(),current.taskId())&&"SUCCEEDED".equals(current.status());
    }
    private static List<Step> replaceFirst(List<Step> steps,String action,Map<String,Object> args){var r=new ArrayList<>(steps);r.set(0,new Step(action,args));return r;}
    private static String derivedKey(String text){return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();}
    private static String safeReply(String reply){return reply==null||reply.isBlank()?"可以继续告诉我你的想法，或说「查看所有功能」。":reply;}
    private void appendResult(String owner,String conversation,Result r){var b=new LinkedHashMap<String,Object>();b.put("text",r.reply());b.put("kind",r.kind());b.put("items",r.items());b.put("commands",r.commands());b.put("action",r.action());append(owner,conversation,b);}
    private void append(String owner,String conversation,Map<String,Object> content){conversations.appendMessage(owner,conversation,"assistant",AgentMessage.KIND_TEXT,AgentMessage.STATE_DONE,json.writeValueAsString(content),null);}
    private void progress(String owner,String conversation,String requestKey,String stage,String action,int step,String text){
        if(!progressEnabled)return;
        turns.assertActive(owner,conversation,requestKey);
        var body=new LinkedHashMap<String,Object>();body.put("kind","progress");body.put("stage",stage);body.put("text",text);body.put("requestKey",requestKey);body.put("step",step);
        if(action!=null)body.put("action",action);
        append(owner,conversation,body);
    }
    private SongCard current(String owner){var c=tools.dispatch(AgentTool.GET_CURRENT_SONG,new AgentTool.GetCurrentSongArgs(),owner,UUID.randomUUID().toString()).songCard();return c==null||c.songId()==null||c.songId().isBlank()?null:c;}
    private Snapshot snapshot(String owner,String conversation,String action) {
        return snapshot(owner,conversation,action,null);
    }
    private Snapshot snapshot(String owner,String conversation,String action,Outcome expected) {
        return snapshot(owner,conversation,action,expected,null,null);
    }
    private Snapshot snapshot(String owner,String conversation,String action,Outcome expected,Long afterSeq,Long expectedEpoch) {
        return snapshot(owner,conversation,action,expected,afterSeq,expectedEpoch,0);
    }
    private Snapshot snapshot(String owner,String conversation,String action,Outcome expected,Long afterSeq,Long expectedEpoch,int retry) {
        long startedEpoch=conversations.historyEpoch(owner,conversation);
        var history=conversations.listRecentMessages(owner,conversation,80);
        var page=conversations.historyPage(owner,conversation,null,40);
        long epoch=page.historyEpoch();boolean delta=afterSeq!=null&&afterSeq>=0&&afterSeq<=page.latestSeq()&&expectedEpoch!=null&&expectedEpoch==epoch;
        List<AgentMessage> visible;
        if(delta){
            var merged=new TreeMap<Long,AgentMessage>();
            conversations.listMessagesAfter(owner,conversation,afterSeq,100).forEach(m->merged.put(m.seq(),m));
            // The user turn changes RUNNING -> DONE without changing seq; upsert its latest version.
            for(int i=history.size()-1;i>=0;i--)if("user".equals(history.get(i).role())){var m=history.get(i);if(m.seq()<=afterSeq)merged.put(m.seq(),m);break;}
            visible=List.copyOf(merged.values());
        }else visible=page.messages();
        var messages=visible.stream().map(m->new MessageView(m.id(),m.conversationId(),m.seq(),m.role(),m.kind(),m.state(),m.contentJson(),m.taskId(),m.createdAt()==null?"":m.createdAt().toString())).toList();
        SongCard card=current(owner);String shareUrl=null;
        if(action!=null&&CLIENT_MEDIA.contains(action)&&(expected==null||!sameMedia(expected,card)))action=null;
        if(card!=null&&card.revisionId()!=null&&card.assetId()!=null){
            var actions=conversations.listRecentActionMessages(owner,conversation,40);
            for(int i=actions.size()-1;i>=0;i--){
                var content=json.readTree(actions.get(i).contentJson());
                if(!Objects.equals(card.songId(),content.path("songId").asText(null)))continue;
                if("revoke_share".equals(content.path("action").asText()))break;
                if(!"share".equals(content.path("action").asText())||!Objects.equals(card.revisionId(),content.path("revisionId").asText(null))||!Objects.equals(card.assetId(),content.path("assetId").asText(null)))continue;
                String stored=content.path("shareUrl").asText("");if(!stored.isBlank()){shareUrl=stored;break;}
            }
        }
        var p=pending(history);Continuation continuation=p!=null&&p.automatic()&&pendingValid(p,card,true)?new Continuation(p.continuationKey(),CONTINUE):null;
        long oldest=messages.isEmpty()?0:messages.get(0).seq();
        long latest=delta?(messages.isEmpty()?afterSeq:Math.max(afterSeq,messages.get(messages.size()-1).seq())):page.latestSeq();
        if(startedEpoch!=epoch||conversations.historyEpoch(owner,conversation)!=epoch){
            if(retry==0)return snapshot(owner,conversation,action,expected,afterSeq,expectedEpoch,1);
            throw new DomainException("AGENT_CONVERSATION_CONFLICT","会话正在更新，请重新读取当前状态。");
        }
        return new Snapshot(messages,card,card==null?null:card.taskId(),action,shareUrl,social!=null&&card!=null&&social.liked(owner,card),continuation,delta?"append":"replace",page.hasOlderMessages(),oldest,latest,epoch,delta&&page.latestSeq()>latest);
    }
    private static boolean sameMedia(Outcome expected,SongCard card) {
        return card!=null&&expected.songId()!=null&&expected.assetId()!=null
            &&Objects.equals(expected.songId(),card.songId())&&Objects.equals(expected.revisionId(),card.revisionId())&&Objects.equals(expected.assetId(),card.assetId());
    }
    private static boolean sameTarget(SongCard expected,SongCard current) {
        return expected!=null&&current!=null&&Objects.equals(expected.songId(),current.songId())
            &&Objects.equals(expected.revisionId(),current.revisionId())&&Objects.equals(expected.assetId(),current.assetId())
            &&Objects.equals(expected.taskId(),current.taskId());
    }
    private static boolean needsResolvedSocialTarget(String action,String text) {
        if(text==null)return false;
        // A model omitting select_work must not redirect a named/numbered request to current work.
        if(java.util.regex.Pattern.compile("第[一二三四五六七八九十百0-9]+|(?:上|下)(?:一)?(?:首|版)|[《》“”‘’「」\\\"']|workId|revisionId|assetId",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find())return true;
        for(String clause:text.split("[，。；,;\\n]|(?:然后|接着|并且|并|再)")) {
            if(!AgentChatPolicy.explicitAction(action,clause))continue;
            String targetClause=clause.strip().replaceFirst("^(?:生成完成后|生成好后|完成之后|完成后)","").strip();
            if(action.equals(AgentChatPolicy.currentSocial(targetClause)))continue;
            if(targetClause.matches("(?i)(?:please )?(?:share|play|download|like|unlike|cancel)(?: (?:it|this(?: song| work)?|the current (?:song|work|task)))?[.!]*"))continue;
            return true;
        }
        return false;
    }
    private static boolean versionReference(String text) {
        return text!=null&&java.util.regex.Pattern.compile("(?:上|下)(?:一)?版|第[一二三四五六七八九十百0-9]+版|某版|版本|音频|revisionId|assetId|previous version",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    private static boolean requiresWorkResolution(String text) {
        if(!versionReference(text))return true;
        // Only these unambiguous current-work version requests can skip selecting the work.
        // Everything more complex needs both bindings, rather than guessing what a name means.
        String version="(?:当前(?:作品|歌曲)的?)?(?:上一版|下一版|第[一二三四五六七八九十0-9]+版)";
        String action="(?:分享|播放|试听|下载|点赞|取消点赞|撤销分享)";
        String simple="(?:(?:请|帮我|请帮我)?(?:"+action+version+"|(?:切回|切换到|选择)"+version+"(?:并|然后|再)?"+action+"(?:它|这版)?))";
        return !text.strip().replaceFirst("[。.!！]+$","").matches("(?i)(?:"+simple+"|(?:please )?(?:share|play|download) (?:the )?previous version)");
    }
    private AgentApplicationActions executor(String action){return applications.stream().filter(a->a.supports(action)).findFirst().orElse(null);}
    private Capability capability(String action){return capabilities().stream().filter(c->c.id().equals(action)).findFirst().orElseThrow(()->new DomainException("UNKNOWN_AGENT_ACTION","这项功能尚未接入，当前没有执行任何后续操作。"));}
    public List<Capability> capabilities() {
        List<Capability> all=new ArrayList<>(List.of(
            new Capability("generate_song","创建新歌或用当前歌词生成音乐；不是无缝延长","{text,mode:new|regenerate,language:zh|en|yue,style,bpm,vocal,instruments}","paid"),
            new Capability("generate_lyrics","新写或基于当前歌词改写","{text,mode:new|rewrite,language,revisionId}","paid"),
            new Capability("set_style","修改当前创作参数；不生成音频","{style,mood,bpm,language,instruments,vocal}","write"),
            new Capability("modify_section","替换已生成音频的明确时间片段","{startS,endS,replacementLyrics}","paid"),
            new Capability("get_current_song","查看当前作品状态","{}","read"),
            new Capability("list_capabilities","列出所有已接入用户功能","{}","read")
        ));
        for(String action:SOCIAL)all.add(new Capability(action,switch(action){case "share"->"创建当前作品七天分享链接";case "revoke_share"->"撤销当前作品分享链接";case "like"->"点赞当前作品";case "unlike"->"取消当前作品点赞";case "play"->"播放当前作品";case "download"->"下载当前作品";default->"按任务现有规则取消当前生成";},"{}","write"));
        for(var app:applications)all.addAll(app.capabilities());return List.copyOf(all);
    }
    private List<AgentChatModel.Turn> history(List<AgentMessage> messages) {
        List<AgentChatModel.Turn> h=new ArrayList<>();
        for(var m:messages){if(!AgentMessage.KIND_TEXT.equals(m.kind()))continue;var body=json.readTree(m.contentJson());if("progress".equals(body.path("kind").asText()))continue;String t=body.path("text").asText("");if(!t.isBlank())h.add(new AgentChatModel.Turn(m.role(),t));}
        return h.subList(Math.max(0,h.size()-20),h.size());
    }
    private String results(List<AgentMessage> messages) {
        List<Map<String,Object>> values=new ArrayList<>();
        for(int i=messages.size()-1;i>=0&&values.size()<6;i--){var m=messages.get(i);if(!"assistant".equals(m.role())||!AgentMessage.KIND_TEXT.equals(m.kind()))continue;var b=json.readTree(m.contentJson());if(!b.path("items").isArray()||b.path("items").isEmpty())continue;var v=new LinkedHashMap<String,Object>();v.put("kind",b.path("kind").asText());v.put("items",json.convertValue(b.path("items"),List.class));values.add(v);}
        Collections.reverse(values);return json.writeValueAsString(values);
    }
    private Object arguments(AgentTool tool,Map<String,Object> args,String text,SongCard current) {
        String instructions=string(args,"text");if(instructions==null||instructions.isBlank())instructions=text;
        String language=string(args,"language");if(language!=null&&language.isBlank())language=null;
        String revision=string(args,"revisionId");if((revision==null||revision.isBlank())&&current!=null)revision=current.revisionId();
        String mode=string(args,"mode");
        return switch(tool){
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
        case EXTEND_SONG -> "音频无缝延长暂未提供。";
        case GET_CURRENT_SONG -> "这是当前作品的最新状态。";
    };}
}
