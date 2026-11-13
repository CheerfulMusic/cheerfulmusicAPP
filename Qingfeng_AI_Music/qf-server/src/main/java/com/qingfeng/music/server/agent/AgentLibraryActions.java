package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentApplicationActions;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.cover.api.CoverApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.hotsong.HotSongService;
import com.qingfeng.music.work.api.WorkApi;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Adapts existing owned business APIs. Model-supplied identifiers are always re-authorized. */
public final class AgentLibraryActions implements AgentApplicationActions {
    private final WorkApi works;
    private final LyricsApi lyrics;
    private final MediaApi media;
    private final GenerationApi generation;
    private final CoverApi covers;
    private final ConversationService conversations;
    private final HotSongService hot;
    private final InspirationRewriteService inspiration;
    private com.qingfeng.music.server.query.WorkLibraryQuery library;
    private final ObjectMapper json = new ObjectMapper();
    public AgentLibraryActions(WorkApi works, LyricsApi lyrics, MediaApi media, GenerationApi generation,
                               CoverApi covers, ConversationService conversations, HotSongService hot,
                               InspirationRewriteService inspiration) {
        this.works=works; this.lyrics=lyrics; this.media=media; this.generation=generation;
        this.covers=covers; this.conversations=conversations; this.hot=hot; this.inspiration=inspiration;
    }
    public AgentLibraryActions withLibrary(com.qingfeng.music.server.query.WorkLibraryQuery library) { this.library=library; return this; }
    public List<Capability> capabilities() { return List.of(
        cap("create_work","新建空白作品草稿并选中，不生成歌词或音乐、不收费。用户自带歌词或音频且没有作品时可先执行此动作","title?:100字内标题,recipient?:80字内收礼人,occasion?:32字内场景,story?:4000字内故事,message?:500字内寄语","write"),
        cap("list_works","列出我的作品，按标题、故事、歌词关键词查询（每页20首，非语义搜索）","query?:关键词,page?:0起页码","read"),
        cap("select_work","打开另一首我的作品；同名时先列出候选，绝不猜测","workId?:作品ID,index?:上次作品列表的1起序号,query?:标题","write"),
        cap("rename_work","修改作品名称，保留其他创作设定","workId?:作品ID,title:新名称","write"),
        cap("update_work","修改创作故事、收礼人、场景、寄语或作品标题，只更新明确提供的字段","workId?:作品ID,recipient?:80字内收礼人,occasion?:32字内场景,story?:4000字内故事,message?:500字内寄语,title?:100字内标题","write"),
        cap("delete_work","删除指定作品，必须展示具体作品并确认","workId?:作品ID,expectedVersion:确认时的作品版本","destructive"),
        cap("list_versions","查看当前或指定作品的歌词历史版本","workId?:作品ID","read"),
        cap("select_version","切换当前作品歌词版本","workId?:作品ID,revisionId?:版本ID,index?:上次版本列表序号","write"),
        cap("replace_lyric_line","按用户明确给定的文字精确替换当前歌词第一句或最后一句，保留其他文本，不调用写词模型","","write"),
        cap("save_lyrics","保存用户提供的完整歌词与标题，不调用生成模型","workId?:作品ID,title:标题,lyrics:完整歌词,baseRevisionId?:基础版本","write"),
        cap("list_audio","列出作品所有音频候选及历史版本","workId?:作品ID","read"),
        cap("select_audio","选择作品的某个真实音频候选，切换对应歌词。导入音频仅在用户明确指定时关联歌词","workId?:作品ID,assetId?:音频ID,index?:上次音频列表序号,revisionId?:用户明确指定的导入音频歌词版本","write"),
        cap("list_covers","查看作品封面及AI改图任务","workId?:作品ID","read"),
        cap("select_cover","选用已有封面","workId?:作品ID,coverId?:封面ID,index?:上次封面列表序号","write"),
        cap("edit_cover","按提示词修改已有封面，需要来源图片；沿用AI改图计费","workId?:作品ID,sourceId?:封面ID,prompt:1至1000字改图要求","paid"),
        cap("upload_cover","在对话内打开封面上传，需要用户选择文件","workId?:作品ID","native"),
        cap("upload_audio","在对话内打开歌曲音频导入，需要用户选择文件","workId?:作品ID","native"),
        cap("discover_music","浏览平台内置推荐试听曲，或查看我的收藏","query?:曲名或曲风,favoritesOnly?:true","read"),
        cap("favorite_music","收藏指定推荐试听曲","trackId:推荐曲目ID","write"),
        cap("unfavorite_music","取消收藏指定推荐试听曲","trackId:推荐曲目ID","write"),
        cap("like_sample","点赞指定推荐试听曲","trackId:推荐曲目ID","write"),
        cap("unlike_sample","取消点赞指定推荐试听曲","trackId:推荐曲目ID","write"),
        cap("rewrite_inspiration","润色用户提供的创作灵感；沿用免费次数和积分计费","text:1至4000字灵感,language?:zh-CN/en/mixed","paid"),
        cap("list_tasks","查看作品生成任务和失败状态","workId?:作品ID","read"),
        cap("retry_archive","重试保存已生成但归档失败的音频，不重复生成","workId?:作品ID,taskId:任务ID","write"),
        cap("close_checking","结束结果待核实任务，必须说明可能仍有供应商结果并确认","workId?:作品ID,taskId:任务ID","destructive")
    ); }
    private static Capability cap(String id,String description,String parameters,String effect) { return new Capability(id,description,parameters,effect); }

    @Override public Map<String,Object> prepare(String owner,String conversation,String action,Map<String,Object> args,SongCard current) {
        conversations.state(owner,conversation);
        if (!Set.of("delete_work","close_checking").contains(action)) return args;
        WorkApi.Work work;
        try { work=work(owner,conversation,args,current); }
        catch (AmbiguousWork ambiguous) { throw invalid("有多首同名作品，请先列出作品并选择具体一首，再确认此操作。"); }
        var prepared=new LinkedHashMap<>(args);
        prepared.put("workId",work.id()); prepared.put("title",displayTitle(owner,work)); prepared.put("expectedVersion",work.version());
        if ("close_checking".equals(action)) {
            String task=required(args,"taskId",80);
            var selected=generation.list(owner,work.id()).stream().filter(t->t.id().equals(task)).findFirst()
                .orElseThrow(()->invalid("未找到这首作品的任务。"));
            prepared.put("taskId",selected.id()); prepared.put("status",selected.status().name());
        }
        return prepared;
    }

    @Override public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current) {
        conversations.state(owner,conversation);
        if ("create_work".equals(action)) {
            String title=field(args,"title","智能创作",100); if(title.isBlank())title="智能创作";
            var draft=new WorkApi.Draft(field(args,"recipient","",80),field(args,"occasion","",32),field(args,"story","",4000),field(args,"message","",500),"",title,"AUTO");
            var created=works.create(owner,draft,key);
            point(owner,conversation,created.id(),null,null,null);
            return new Result("已新建并打开作品草稿，可以保存你的歌词或上传音频。本次未生成歌词或音乐。","works",
                List.of(new Item(created.id(),created.title(),"空白作品草稿",Map.of("workId",created.id(),"version",created.version()))),List.of(),action);
        }
        if ("list_works".equals(action)) return listWorks(owner,args,null);
        if ("discover_music".equals(action)) return discover(owner,args);
        if (Set.of("favorite_music","unfavorite_music","like_sample","unlike_sample").contains(action)) return reaction(owner,action,args);
        if ("rewrite_inspiration".equals(action)) return ok(action,inspiration.rewrite(owner,required(args,"text",4000),value(args,"language","zh-CN"),key));
        if (!supports(action)) throw invalid("不支持的作品操作。");
        WorkApi.Work work;
        if ("select_work".equals(action) && args.containsKey("index") && !args.containsKey("workId")) {
            var resolved = new LinkedHashMap<>(args);
            resolved.put("workId",fromResult(owner,conversation,"works","workId",null,args,true));
            args = resolved;
        }
        try { work=work(owner,conversation,args,current); }
        catch (AmbiguousWork ambiguous) { return listWorks(owner,args,"找到多首同名作品，请从列表选择具体一首。"); }
        String id=work.id();
        return switch(action) {
            case "select_work" -> { point(owner,conversation,id,null,null,null); yield ok(action,"已打开《"+displayTitle(owner,work)+"》，接下来的创作和操作将使用这首作品。"); }
            case "rename_work" -> {
                String title=required(args,"title",100);
                works.update(owner,id,work.version(),new WorkApi.Draft(work.recipient(),work.occasion(),work.story(),work.message(),work.style(),title,work.vocalGender()));
                if(id.equals(conversations.current(owner).currentWorkId())) {
                    var state=conversations.state(owner,conversation);
                    var snapshot=state.currentSongJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(AgentJson.read(state.currentSongJson()));
                    snapshot.put("displayTitle",title);
                    conversations.applyToolResult(owner,conversation,null,"{}",id,AgentJson.write(snapshot),null,state.revision());
                }
                yield ok(action,"作品已改名为《"+title+"》。");
            }
            case "update_work" -> {
                if(java.util.stream.Stream.of("recipient","occasion","story","message","title").map(args::get).noneMatch(value->value instanceof String textValue&&!textValue.isBlank()))
                    throw invalid("请提供要修改的作品资料；未填写的字段会保留原值。");
                var draft=new WorkApi.Draft(field(args,"recipient",work.recipient(),80),field(args,"occasion",work.occasion(),32),field(args,"story",work.story(),4000),field(args,"message",work.message(),500),work.style(),field(args,"title",work.title(),100),work.vocalGender());
                works.update(owner,id,work.version(),draft);
                if(args.containsKey("title")&&id.equals(conversations.current(owner).currentWorkId())) {
                    var state=conversations.state(owner,conversation);var snapshot=state.currentSongJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(AgentJson.read(state.currentSongJson()));
                    snapshot.put("displayTitle",draft.title());conversations.applyToolResult(owner,conversation,null,"{}",id,AgentJson.write(snapshot),null,state.revision());
                }
                yield ok(action,"作品资料已更新。已生成的歌词和音频保持原版本，可继续要求改词或重新生成。");
            }
            case "delete_work" -> {
                if (!args.containsKey("expectedVersion")||number(args,"expectedVersion",-1)!=work.version()) throw invalid("作品在确认后发生了变化，请重新查看并确认删除。");
                if(generation.list(owner,id).stream().anyMatch(t->!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(t.status().name())))throw invalid("作品还有生成或核实中的任务，请先处理任务再删除。");
                var coverState=covers.state(owner,id);
                if(coverState!=null&&coverState.edits().stream().anyMatch(e->!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(e.status())))throw invalid("作品还有封面处理任务，请等待任务结束后删除。");
                String deletedTitle=displayTitle(owner,work);
                works.delete(owner,id,true,work.version());
                if (id.equals(conversations.current(owner).currentWorkId())) point(owner,conversation,null,null,null,null);
                yield ok(action,"已删除《"+deletedTitle+"》。");
            }
            case "list_versions" -> listVersions(owner,work);
            case "select_version" -> {
                String revision=selectedId(owner,conversation,args,"revisionId","versions",id);
                var selected=lyrics.get(owner,id,revision);
                point(owner,conversation,id,selected.id(),null,null);
                yield ok(action,"已切换到所选歌词版本《"+selected.title()+"》。生成或改词将以此版本为基础。");
            }
            case "replace_lyric_line" -> {
                var request=com.qingfeng.music.agent.application.ExactLyricEdit.parse(text);
                if(request==null)throw invalid("请明确指定第一句或最后一句，以及替换文字。");
                if(current==null||current.revisionId()==null||!id.equals(current.songId()))throw invalid("请先打开要修改的作品和歌词版本。");
                var state=conversations.state(owner,conversation);
                var active=conversations.current(owner);
                if(!conversation.equals(active.id())||!id.equals(active.currentWorkId()))throw invalid("当前会话或作品已切换，请重新打开要修改的歌词。");
                if(state.currentSongJson()!=null){var selected=AgentJson.read(state.currentSongJson());Object revision=selected.get("revisionId");if(revision!=null&&!current.revisionId().equals(revision))throw invalid("当前歌词版本已切换，请重新确认要修改的版本。");}
                var base=lyrics.get(owner,id,current.revisionId());
                if(base.workVersion()!=work.version())throw invalid("作品已修改，请重新选择当前歌词版本。");
                String updated=request.apply(base.lyrics());
                if(updated.equals(base.lyrics()))yield ok(action,"当前"+request.position()+"句已经是：\n"+request.replacement()+"\n无需重复修改。");
                var saved=lyrics.save(owner,id,work.version(),base.title(),updated,base.id());
                var confirmed=lyrics.confirm(owner,id,saved.id());point(owner,conversation,id,confirmed.id(),null,null);
                yield ok(action,"已将《"+base.title()+"》的"+request.position()+"句修改为：\n"+request.replacement()+"\n其他歌词保持不变，已保存。已有音频不会随歌词修改而改变。");
            }
            case "save_lyrics" -> {
                var revision=lyrics.save(owner,id,work.version(),required(args,"title",100),required(args,"lyrics",12000),value(args,"baseRevisionId",null));
                var confirmed=lyrics.confirm(owner,id,revision.id()); point(owner,conversation,id,confirmed.id(),null,null);
                yield ok(action,"你的歌词已保存并选中，没有提交音乐生成。");
            }
            case "list_audio" -> listAudio(owner,work);
            case "select_audio" -> {
                String assetId=selectedId(owner,conversation,args,"assetId","audio",id);
                var asset=media.get(owner,id,assetId);
                var task=asset.generationTaskId()==null?null:generation.list(owner,id).stream().filter(t->t.id().equals(asset.generationTaskId())).findFirst().orElse(null);
                if (!"IMPORTED".equals(asset.origin())&&task==null) throw invalid("这段音频还没有可验证的歌词版本，暂不能切换。");
                String revision=task==null?null:task.revisionId();
                if("IMPORTED".equals(asset.origin())&&args.containsKey("revisionId")) {
                    var associated=lyrics.get(owner,id,required(args,"revisionId",80));
                    if(!associated.confirmed()||associated.workVersion()!=work.version())throw invalid("请先保存并确认当前版本的歌词，再关联导入音频。");
                    revision=associated.id();
                }
                var selection=media.selection(owner,id); media.select(owner,id,assetId,selection.version());
                point(owner,conversation,id,revision,task==null?null:task.id(),assetId);
                yield ok(action,"已选用这段音频，播放和下载会使用所选版本。"+("IMPORTED".equals(asset.origin())&&revision==null?"如需分享，请明确指定要关联的已确认歌词版本。":"分享也会使用所选版本。"));
            }
            case "list_covers" -> listCovers(owner,work);
            case "select_cover" -> {
                String cover=selectedId(owner,conversation,args,"coverId","covers",id);
                var state=covers.state(owner,id); covers.select(owner,id,cover,state.version());
                yield ok(action,"已选用这张封面。");
            }
            case "edit_cover" -> {
                var state=covers.state(owner,id); String source=value(args,"sourceId",state.currentCoverId());
                if (source==null) yield new Result("请先上传或选用一张封面，再描述希望怎样修改。","covers",List.of(),List.of(new Command("上传封面","cover:"+id,"upload")),null);
                var edit=covers.edit(owner,id,source,required(args,"prompt",1000),key);
                yield new Result("封面修改任务已提交，可查询封面列表查看处理状态。","covers",List.of(new Item(edit.id(),"封面修改",edit.status(),Map.of("workId",id,"status",edit.status()))),List.of(send("查看封面","查看作品 "+id+" 的封面")),action);
            }
            case "upload_cover","upload_audio" -> new Result("请选择需要上传的"+(action.equals("upload_cover")?"封面图片":"歌曲音频")+"。","upload",List.of(),List.of(new Command("选择文件",(action.equals("upload_cover")?"cover:":"audio:")+id,"upload")),action);
            case "list_tasks" -> listTasks(owner,work);
            case "retry_archive" -> { generation.retryArchive(owner,id,required(args,"taskId",80)); yield ok(action,"已请求重新归档音频，可查看任务状态。"); }
            case "close_checking" -> {
                if (!args.containsKey("expectedVersion")||number(args,"expectedVersion",-1)!=work.version()) throw invalid("作品状态已变化，请重新确认结束核实。");
                generation.closeChecking(owner,id,required(args,"taskId",80),true); yield ok(action,"该任务已结束核实，请以账户实际记账和任务结果为准。");
            }
            default -> throw invalid("不支持的作品操作。");
        };
    }

    private Result listWorks(String owner,Map<String,Object> args,String clarification) {
        int page=(int)number(args,"page",0); if(page<0||page>10000) throw invalid("作品页码超出范围。");
        String query=value(args,"query","").toLowerCase(Locale.ROOT);
        List<LibraryWork> found=search(owner,query,page);
        var items=found.stream().map(w->new Item(w.work().id(),w.title(),w.status()+" · 作品版本 "+w.work().version(),Map.<String,Object>of("workId",w.work().id(),"version",w.work().version(),"createdAt",w.work().createdAt().toString(),"status",w.status()))).toList();
        var commands=new ArrayList<Command>(); found.stream().limit(10).forEach(w->commands.add(send("打开「"+w.title()+"」","打开作品 "+w.work().id())));
        if(found.size()==20) commands.add(send("下一页","列出我的作品，第 "+(page+2)+" 页（page="+(page+1)+"）"+(query.isBlank()?"":"，关键词："+query)));
        return new Result(clarification!=null?clarification:found.isEmpty()?"没有找到符合条件的作品。":"这是你的作品。可以告诉我打开第几首，或按关键词查询。"+(query.isBlank()?"":"（匹配标题、故事和歌词文字，非语义搜索）"),"works",items,commands,clarification==null?"list_works":null);
    }
    private record LibraryWork(WorkApi.Work work,String title,String status) {}
    private List<LibraryWork> libraryPage(String owner,int page) {
        if(library==null) return works.list(owner,page).stream().map(w->new LibraryWork(w,title(w),"作品")).toList();
        return library.list(owner,page).stream().map(item->{var w=item.work();String display=w.title();if(display==null||display.isBlank()||"智能创作".equals(display))display=item.songTitle();return new LibraryWork(w,display==null||display.isBlank()?title(w):display,item.status());}).toList();
    }
    private List<LibraryWork> search(String owner,String query,int page) {
        if(query.isBlank()) return libraryPage(owner,page);
        if(library==null)return libraryPage(owner,page).stream().filter(w->w.title().toLowerCase(Locale.ROOT).contains(query)).toList();
        return library.search(owner,query,page).stream().map(item->{var w=item.work();String display=w.title();if(display==null||display.isBlank()||"智能创作".equals(display))display=item.songTitle();return new LibraryWork(w,display==null||display.isBlank()?title(w):display,item.status());}).toList();
    }
    private WorkApi.Work work(String owner,String conversation,Map<String,Object> args,SongCard current) {
        String id=value(args,"workId",null);
        if(id==null&&args.containsKey("index")&&!args.containsKey("revisionId")&&!args.containsKey("assetId")&&!args.containsKey("coverId")) {
            // Index applies to work only when the last structured result is a work list.
            id=fromResult(owner,conversation,"works","workId",null,args,false);
        }
        if(id==null&&args.containsKey("query")) {
            String query=value(args,"query","").trim(); if(query.isBlank()) throw invalid("请给出作品名。");
            var candidates=search(owner,query.toLowerCase(Locale.ROOT),0);
            var exact=candidates.stream().filter(w->w.title().equalsIgnoreCase(query)).toList(); if(!exact.isEmpty()) candidates=exact;
            if(candidates.size()>1) throw new AmbiguousWork();
            if(candidates.isEmpty()) throw invalid("没有找到这首作品，请先列出我的作品再选择。");
            id=candidates.get(0).work().id();
        }
        if(id==null&&current!=null) id=current.songId();
        if(id==null||id.isBlank()) throw invalid("请先选择一首作品，可以说「列出我的作品」。");
        return works.get(owner,id);
    }
    private Result listVersions(String owner,WorkApi.Work work) {
        var revisions=new ArrayList<>(lyrics.list(owner,work.id())); revisions.sort(Comparator.comparing(LyricsApi.Revision::createdAt,Comparator.nullsLast(Comparator.reverseOrder())));
        var items=revisions.stream().limit(50).map(r->new Item(r.id(),r.title(),(r.confirmed()?"已确认":"草稿")+" · "+r.createdAt(),Map.<String,Object>of("workId",work.id(),"revisionId",r.id(),"version",r.workVersion()))).toList();
        var commands=items.stream().limit(10).map(i->send("选择第 "+(items.indexOf(i)+1)+" 版","选择作品 "+work.id()+" 的歌词版本 "+i.id())).toList();
        return new Result(items.isEmpty()?"这首作品还没有歌词版本。":"这是歌词版本列表，最新版本在前。","versions",items,commands,"list_versions");
    }
    private Result listAudio(String owner,WorkApi.Work work) {
        var selection=media.selection(owner,work.id()); var assets=new ArrayList<>(media.list(owner,work.id()));
        assets.sort(Comparator.comparing(MediaApi.Asset::createdAt,Comparator.nullsLast(Comparator.reverseOrder())));
        var items=assets.stream().limit(50).map(a->new Item(a.id(),"音频 "+(assets.indexOf(a)+1),a.origin()+" · "+a.createdAt()+(a.id().equals(selection.assetId())?" · 当前选用":""),Map.<String,Object>of("workId",work.id(),"assetId",a.id(),"current",a.id().equals(selection.assetId()),"audioUrl","/api/app/works/"+work.id()+"/audio/"+a.id()))).toList();
        return new Result(items.isEmpty()?"这首作品还没有音频。":"选择一个音频候选后，会同步对应歌词版本。","audio",items,items.stream().limit(10).map(i->send("选用「"+i.title()+"」","选择作品 "+work.id()+" 的音频 "+i.id())).toList(),"list_audio");
    }
    private Result listCovers(String owner,WorkApi.Work work) {
        var state=covers.state(owner,work.id());
        var items=new ArrayList<Item>();
        for(var a:state.assets()) items.add(new Item(a.id(),"封面 "+(items.size()+1),a.origin()+(a.id().equals(state.currentCoverId())?" · 当前选用":""),Map.of("workId",work.id(),"coverId",a.id(),"current",a.id().equals(state.currentCoverId()),"imageUrl","/api/app/works/"+work.id()+"/covers/"+a.id())));
        var commands=new ArrayList<Command>(); items.stream().limit(10).forEach(i->commands.add(send("选用「"+i.title()+"」","选择作品 "+work.id()+" 的封面 "+i.id())));
        for(var edit:state.edits()) items.add(new Item(edit.id(),"封面修改任务",edit.status(),Map.of("workId",work.id(),"status",edit.status())));
        commands.add(new Command("上传封面","cover:"+work.id(),"upload"));
        return new Result("这是作品封面及修改状态。"+(state.aiAvailable()?"可描述如何修改当前封面。":"当前AI改图服务未启用。"),"covers",items,commands,"list_covers");
    }
    private Result listTasks(String owner,WorkApi.Work work) {
        var tasks=generation.list(owner,work.id());
        var items=tasks.stream().limit(50).map(t->new Item(t.id(),t.title(),t.status().name()+(t.errorCode()==null?"":" · "+t.errorCode()),Map.<String,Object>of("workId",work.id(),"taskId",t.id(),"status",t.status().name()))).toList();
        return new Result(items.isEmpty()?"这首作品还没有生成任务。":"这是作品生成任务的实际状态。","tasks",items,List.of(),"list_tasks");
    }

    private String selectedId(String owner,String conversation,Map<String,Object> args,String key,String kind,String work) {
        String id=value(args,key,null); if(id!=null) return id;
        id=fromResult(owner,conversation,kind,key,work,args,true); if(id!=null) return id;
        throw invalid("请先查看列表，再指定序号或具体版本。");
    }
    private String fromResult(String owner,String conversation,String kind,String key,String work,Map<String,Object> args,boolean required) {
        if(!args.containsKey("index")) return null;
        long ordinal=number(args,"index",0); if(ordinal<1||ordinal>50) throw invalid("列表序号应从1开始。");
        var history=conversations.listRecentMessages(owner,conversation,80);
        for(int n=history.size()-1;n>=0;n--) {
            if(!"assistant".equals(history.get(n).role())) continue;
            var body=json.readTree(history.get(n).contentJson());
            if(!body.has("items")) continue;
            // Only the most recent visible list can resolve an ordinal: older lists may mean something else.
            if(!kind.equals(body.path("kind").asText())) break;
            var items=body.path("items");
            if(ordinal>items.size()) throw invalid("列表没有这个序号，请重新选择。");
            var data=items.get((int)ordinal-1).path("data");
            if(work!=null&&!work.equals(data.path("workId").asText())) throw invalid("该列表属于另一首作品，请重新查看当前作品列表。");
            String result=data.path(key).asText(""); if(!result.isBlank()) return result; break;
        }
        if(required) throw invalid("没有可对应的列表，请先查看列表再选择序号。"); return null;
    }
    private void point(String owner,String conversation,String work,String revision,String task,String asset) {
        var state=conversations.state(owner,conversation);
        boolean same=Objects.equals(work,conversations.current(owner).currentWorkId());
        var snapshot=same&&state!=null&&state.currentSongJson()!=null?new LinkedHashMap<>(AgentJson.read(state.currentSongJson())):new LinkedHashMap<String,Object>();
        snapshot.put("revisionId",revision); snapshot.put("taskId",task); snapshot.put("assetId",asset);
        if(work!=null) {
            String display=works.get(owner,work).title();
            if(display!=null&&!display.isBlank()&&!"智能创作".equals(display))snapshot.put("displayTitle",display);
        }
        conversations.applyToolResult(owner,conversation,null,"{}",work,work==null?null:AgentJson.write(snapshot),task,state==null?0:state.revision());
    }
    private Result discover(String owner,Map<String,Object> args) {
        var reactions=hot.reactions(owner); String query=value(args,"query","").toLowerCase(Locale.ROOT);
        boolean favorites=Boolean.parseBoolean(value(args,"favoritesOnly","false"));
        var items=SAMPLES.stream().filter(s->(s.title()+s.style()).toLowerCase(Locale.ROOT).contains(query))
            .filter(s->!favorites||reactions.favorited().contains(s.id()))
            .map(s->new Item(s.id(),s.title(),"平台试听样曲 · "+s.style(),Map.<String,Object>of("trackId",s.id(),"audioUrl",s.audio(),"liked",reactions.liked().contains(s.id()),"favorited",reactions.favorited().contains(s.id())))).toList();
        return new Result(items.isEmpty()?"没有符合条件的试听曲。":"这些是平台内置试听曲，可以试听、点赞或收藏。","discover",items,items.stream().map(i->send((Boolean.TRUE.equals(i.data().get("favorited"))?"取消收藏":"收藏")+"「"+i.title()+"」",(Boolean.TRUE.equals(i.data().get("favorited"))?"取消收藏":"收藏")+"推荐歌曲 "+i.id())).toList(),"discover_music");
    }
    private Result reaction(String owner,String action,Map<String,Object> args) {
        String track=required(args,"trackId",80); boolean favorite=action.contains("favorite"),active=!action.startsWith("un");
        var toggle=hot.react(owner,track,favorite?"favorite":"like",active);
        var sample=SAMPLES.stream().filter(s->s.id().equals(track)).findFirst().orElseThrow(()->invalid("推荐曲目不存在。"));
        return new Result((active?"已":"已取消")+(favorite?"收藏":"点赞")+"《"+sample.title()+"》。","discover",List.of(new Item(track,sample.title(),"平台试听样曲",Map.of("trackId",track,"liked",toggle.liked(),"favorited",toggle.favorited(),"audioUrl",sample.audio()))),List.of(),action);
    }
    private record Sample(String id,String title,String style,String audio) {}
    private static final List<Sample> SAMPLES=List.of(
        new Sample("love","把平凡唱给你","爱情 温柔陪伴","/media/sample-love-v2.mp3"),
        new Sample("family","走了那么远","家庭 抒情流行","/media/sample-family-v2.mp3"),
        new Sample("travel","去有海风的地方","自由旅行","/media/sample-ocean-travel-v2.mp3"),
        new Sample("red-guofeng","红","古风 国风","/media/sample-red-guofeng-v1.mp3"),
        new Sample("rain-disco","忽然下起雨","Disco 复古","/media/sample-rain-disco-v1.mp3"),
        new Sample("tomorrow-rock","明天再说","摇滚","/media/sample-tomorrow-rock-v1.mp3"),
        new Sample("scenery-folk","最美的风景","民谣","/media/sample-scenery-folk-v1.mp3"),
        new Sample("hometown-electronic","hometown lover","电子","/media/sample-hometown-electronic-v1.mp3"),
        new Sample("river-instrumental","When the River Breathes","纯音乐","/media/sample-river-instrumental-v1.mp3"));
    private static String title(WorkApi.Work w) { return w.title()==null||w.title().isBlank()?"未命名作品":w.title(); }
    private String displayTitle(String owner,WorkApi.Work work) {
        if(work.title()!=null&&!work.title().isBlank()&&!"智能创作".equals(work.title()))return work.title();
        var latest=SongCardProjector.latest(lyrics.list(owner,work.id()));return latest==null?title(work):latest.title();
    }
    private static Result ok(String action,String text) { return new Result(text,"text",List.of(),List.of(),action); }
    private static Command send(String label,String text) { return new Command(label,text,"send"); }
    private static String value(Map<String,Object> args,String key,String fallback) { Object value=args.get(key); return value==null||value.toString().isBlank()?fallback:value.toString().trim(); }
    private static String required(Map<String,Object> args,String key,int max) { String text=value(args,key,null); if(text==null||text.length()>max) throw invalid("请提供有效的 "+key+"。"); return text; }
    private static String field(Map<String,Object> args,String key,String fallback,int max) { String text=Objects.toString(args.get(key),"").trim();if(text.isBlank())text=fallback;if(text.length()>max)throw invalid(key+" 超出长度限制。");return text; }
    private static long number(Map<String,Object> args,String key,long fallback) { try { return Long.parseLong(value(args,key,Long.toString(fallback))); } catch(NumberFormatException error) { throw invalid("序号或版本参数无效。"); } }
    private static DomainException invalid(String message) { return new DomainException("INVALID_AGENT_APPLICATION_ARGUMENT",message); }
    private static class AmbiguousWork extends RuntimeException {}
}
