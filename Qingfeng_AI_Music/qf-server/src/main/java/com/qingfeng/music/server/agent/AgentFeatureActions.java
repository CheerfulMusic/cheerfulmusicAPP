package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.creation.QuickCreationService;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.download.SharedDownloadGrants;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** User-facing domain operations only; never forwards model-supplied HTTP requests. */
public final class AgentFeatureActions implements AgentApplicationActions {
 private final WorkApi works;private final LyricsApi lyrics;private final ConversationService conversations;
 private final ArtistProfileService profiles;private final ObjectProvider<QuickCreationService> quick;
 private final LyricSelectionRewriteService selection;private final LyricLineRewriteService lines;
 private final LyricsCompositionService composer;private final MusicSettings settings;private final SharedDownloadGrants downloads;private final CreditsPricing pricing;
 private final ObjectMapper json=new ObjectMapper();
 public AgentFeatureActions(WorkApi works,LyricsApi lyrics,ConversationService conversations,ArtistProfileService profiles,
   ObjectProvider<QuickCreationService> quick,LyricSelectionRewriteService selection,LyricLineRewriteService lines,
   LyricsCompositionService composer,MusicSettings settings,SharedDownloadGrants downloads,CreditsPricing pricing){
  this.works=works;this.lyrics=lyrics;this.conversations=conversations;this.profiles=profiles;this.quick=quick;this.selection=selection;this.lines=lines;this.composer=composer;this.settings=settings;this.downloads=downloads;this.pricing=pricing;
 }
 public List<Capability> capabilities(){return List.of(
  cap("open_feature","在对话内打开完整功能面板：artist艺人主页编辑与图片裁剪、discover发现歌曲、analytics统计（仍需管理权限）、work作品完整编辑、avatar头像上传、reference参考音频分析、gift音乐礼物试听与下载、payment_risk资金审核、editorial精选管理（运营功能仍须独立权限，只能本人在面板确认）","feature:artist/discover/analytics/work/avatar/reference/payment_risk/editorial/gift,workId?,giftToken?:分享链接中的43字符令牌","native"),
  cap("get_artist_profile","查看本人艺人主页及已发布作品","","read"),
  cap("update_artist_profile","修改艺名、简介、主打作品；图片上传或裁剪用open_feature artist；只改提供的字段，公开主页的修改需确认","stageName?,tagline?,featuredReleaseId?","write"),
  cap("set_artist_visibility","公开或隐藏本人艺人主页，先确认","published:boolean","write"),
  cap("get_artist_release","查看作品当前发布版本、音频、封面和发现页状态","workId?","read"),
  cap("publish_artist_song","发布指定音频版本到本人艺人主页，先展示标题、音频、封面并确认","workId?,assetId,title,coverId?","write"),
  cap("remove_artist_song","从艺人主页移除指定作品（不删除原作品），需确认","workId?","destructive"),
  cap("publish_to_discover","将当前已发布版本公开到站内发现页，需确认","workId?","write"),
  cap("withdraw_from_discover","从发现页撤下指定作品，保留原作品，需确认","workId?","destructive"),
  cap("start_quick_creation","以当前保存的故事一键排队写词并谱曲，沿用积分和幂等规则，先确认","workId?,language?:zh-CN/en/mixed","paid"),
  cap("get_quick_creation","查看最新一键创作任务与可重试状态","workId?","read"),
  cap("retry_quick_creation","恢复失败的一键创作，复用原任务规则，不重新购买已生成音乐，先确认","workId?,creationId","paid"),
  cap("get_creative_direction","查看下一次生成实际使用的完整音乐方向","workId?","read"),
  cap("get_lyrics_request","核对原歌词请求状态，结果未知时不要重新生成","workId?,requestKey","read"),
  cap("confirm_lyrics","确认指定现有歌词版本供谱曲，不改写歌词","workId?,revisionId","write"),
  cap("rewrite_lyric_selection","保留指定歌词行并重写其他行，返回预览不自动保存；行号从0开始，包含空行和段落标签","workId?,revisionId,keptLines:行号数组,language?:zh-CN/en/mixed","paid"),
  cap("rhyme_lyric_line","按邻近歌词和指定参考行改押韵，返回预览不自动保存","workId?,line,lines,lineIndex,referenceIndex?,language,allowNear?,allowCrossLanguage?,rewriteIfAlreadyRhymes?","paid"),
  cap("list_shared_downloads","查看本人已经解锁的礼物歌曲并提供下载链接，不购买新授权","","read")
 );}
 private static Capability cap(String id,String description,String params,String effect){return new Capability(id,description,params,effect);}
 private String work(String owner,Map<String,Object> args,SongCard current){String id=value(args,"workId",current==null?null:current.songId());if(id==null)throw invalid("请先选择作品。");works.get(owner,id);return id;}
 private void account(String owner){if(!profiles.hasAccount(owner))throw invalid("请先登录音乐账号。");}
 public Map<String,Object> prepare(String owner,String conversation,String action,Map<String,Object> args,SongCard current){
  var frozen=new LinkedHashMap<>(args);
  if(Set.of("update_artist_profile","set_artist_visibility").contains(action)){
   account(owner);frozen.put("expectedProfile",json.writeValueAsString(profiles.mine(owner)));
   frozen.put("confirmationSummary","将修改本人艺人主页："+json.writeValueAsString(args)+"。已公开的主页可能立即展示这些修改。");
  }
  if(Set.of("publish_artist_song","remove_artist_song","publish_to_discover","withdraw_from_discover","start_quick_creation","retry_quick_creation").contains(action)){
   String w=work(owner,args,current);var song=works.get(owner,w);frozen.put("workId",w);frozen.put("expectedVersion",song.version());
   if(action.contains("artist")||action.contains("discover")){
    account(owner);var release=profiles.selection(owner,w);frozen.put("expectedRelease",json.writeValueAsString(release));
    if(action.equals("publish_to_discover")){if(release.song()==null)throw invalid("请先把该音频发布到艺人主页。");frozen.put("releaseVersion",release.song().version());}
   }
   String summary="作品《"+song.title()+"》；操作 "+action+"；参数 "+json.writeValueAsString(args);
   if(action.equals("start_quick_creation"))summary+="。将写词并谱曲，按现有免费额度及积分规则计费；未抵扣免费额度的标准价格：歌词 "+pricing.cost(CreditsPricing.Kind.LYRICS)+" 积分，音乐 "+pricing.cost(CreditsPricing.Kind.MUSIC)+" 积分。";
   frozen.put("confirmationSummary",summary);
  }
  return frozen;
 }
 public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current){
  conversations.state(owner,conversation);
  if(action.equals("open_feature")){
   String feature=value(args,"feature","");if(!Set.of("artist","discover","analytics","work","avatar","reference","payment_risk","editorial","gift").contains(feature))throw invalid("未知功能面板。");
   var target=new LinkedHashMap<String,Object>();target.put("feature",feature);if(feature.equals("gift")){String token=required(args,"giftToken");if(!token.matches("[A-Za-z0-9_-]{43}"))throw invalid("请提供有效礼物链接。");target.put("giftToken",token);}if(feature.equals("work"))target.put("workId",work(owner,args,current));
   return new Result("请在对话内的操作面板继续，现有账号权限和确认规则保持生效。","feature",List.of(),List.of(new Command("打开操作面板",json.writeValueAsString(target),"feature")),action);
  }
  if(Set.of("get_artist_profile","update_artist_profile","set_artist_visibility").contains(action)){
   account(owner);var prior=profiles.mine(owner);
   if(!action.equals("get_artist_profile")&&!Objects.equals(args.get("expectedProfile"),json.writeValueAsString(prior)))throw invalid("主页已变化，请重新确认。");
   return switch(action){
    case "get_artist_profile"->result(action,"艺人主页",prior);
    case "set_artist_visibility"->{if(!(args.get("published") instanceof Boolean published))throw invalid("请选择公开或隐藏。");yield result(action,"主页可见性已更新",profiles.setPublished(owner,published));}
    default->result(action,"主页已更新",profiles.save(owner,value(args,"stageName",prior.stageName()),value(args,"tagline",prior.tagline()),null,false,null,false,value(args,"featuredReleaseId",prior.featuredReleaseId())));
   };
  }
  if(action.equals("list_shared_downloads")){
   account(owner);return new Result("这些是已解锁的礼物歌曲。","downloads",downloads.list(owner).stream().map(d->new Item(d.id(),d.title(),"已解锁",Map.of("downloadUrl","/api/app/shared-downloads/"+d.id()))).toList(),List.of(),action);
  }
  String w=work(owner,args,current);var song=works.get(owner,w);
  if(args.containsKey("expectedVersion")&&(!(args.get("expectedVersion") instanceof Number n)||n.longValue()!=song.version()))throw invalid("作品已修改，请重新确认。");
  if(Set.of("get_artist_release","publish_artist_song","remove_artist_song","publish_to_discover","withdraw_from_discover").contains(action)){
   account(owner);var prior=profiles.selection(owner,w);
   if(!action.equals("get_artist_release")&&!Objects.equals(args.get("expectedRelease"),json.writeValueAsString(prior)))throw invalid("发布版本已变化，请重新确认。");
   return switch(action){
    case "get_artist_release"->result(action,"发布状态",prior);
    case "publish_artist_song"->result(action,"已发布到艺人主页",profiles.publishSong(owner,w,required(args,"assetId"),value(args,"coverId",null),required(args,"title")));
    case "publish_to_discover"->result(action,"已发布到发现页",profiles.publishToDiscover(owner,w,((Number)args.get("releaseVersion")).longValue()));
    case "withdraw_from_discover"->result(action,"已从发现页撤下",profiles.withdrawFromDiscover(owner,w));
    default->{profiles.removeSong(owner,w);yield result(action,"已从艺人主页移除",profiles.mine(owner));}
   };
  }
  return switch(action){
   case "get_quick_creation"->result(action,"一键创作状态",quick.getObject().latest(owner,w).orElse(null));
   case "start_quick_creation"->result(action,"一键创作已排队",quick.getObject().start(owner,w,((Number)args.get("expectedVersion")).longValue(),key,value(args,"language","zh-CN")));
   case "retry_quick_creation"->result(action,"原一键创作任务",quick.getObject().retry(owner,w,required(args,"creationId")));
   case "get_creative_direction"->result(action,"实际音乐方向",settings.forGeneration(owner,song));
   case "get_lyrics_request"->result(action,"原歌词请求状态",composer.requestState(owner,w,required(args,"requestKey")));
   case "confirm_lyrics"->result(action,"歌词版本已确认",lyrics.confirm(owner,w,required(args,"revisionId")));
   case "rewrite_lyric_selection"->{var revision=lyrics.get(owner,w,required(args,"revisionId"));if(!(args.get("keptLines") instanceof List<?> kept)||kept.stream().anyMatch(x->!(x instanceof Number)))throw invalid("请选择保留行号。");yield result(action,"歌词修改预览（尚未保存）",selection.preview(owner,w,song.version(),UUID.fromString(key),revision.lyrics(),kept.stream().map(x->((Number)x).intValue()).toList(),value(args,"language","zh-CN")));}
   case "rhyme_lyric_line" -> {
    if (!(args.get("lines") instanceof List<?> rows) || rows.stream().anyMatch(x -> !(x instanceof String)))
      throw invalid("请选择邻近歌词。");
    var request = new LyricRhymePreview.Request(required(args,"line"), rows.stream().map(Object::toString).toList(),
      ((Number)args.get("lineIndex")).intValue(), args.get("referenceIndex") instanceof Number n ? n.intValue() : null,
      required(args,"language"), Boolean.TRUE.equals(args.get("allowNear")), Boolean.TRUE.equals(args.get("allowCrossLanguage")),
      Boolean.TRUE.equals(args.get("rewriteIfAlreadyRhymes")));
    yield result(action,"押韵预览（尚未保存）",lines.previewRhyme(owner,w,UUID.fromString(key),request));
   }
   default->throw invalid("不支持的操作。");
  };
 }
 private Result result(String action,String label,Object value){
  String detail;
  if(value==null)detail="暂无记录";
  else if(value instanceof String text)detail=text;
  else if(value instanceof ArtistProfileService.MyProfile p)detail=p.stageName()+" · "+p.tagline()+"\n主页"+(p.published()?"已公开":"未公开")+"；已发布 "+p.songs().size()+" 首作品："+String.join("、",p.songs().stream().map(ArtistProfileService.Release::title).toList());
  else if(value instanceof ArtistProfileService.Release r)detail=r.title()+" · 发布版本 "+r.version()+" · "+(r.onDiscover()?"已在发现页公开":"仅艺人主页");
  else if(value instanceof ArtistProfileService.ReleaseSelection r)detail=(r.published()?"艺人主页已公开":"艺人主页未公开")+"；"+(r.song()==null?"作品尚未发布":r.song().title()+" · 版本 "+r.song().version());
  else if(value instanceof QuickCreationService.View v)detail="创作状态："+v.status()+(v.canRetry()?"；可以重试原任务":"")+(v.errorCode()==null?"":"；本次任务需要处理，请查看作品进度");
  else if(value instanceof MusicSettings.Direction d)detail=d.style()+"\n排除要求："+d.negativeTags();
  else if(value instanceof LyricsCompositionService.RequestState r)detail="歌词请求状态："+r.status()+(r.revisionId()==null?"":"；已保存歌词版本 "+r.revisionId())+(r.errorCode()==null?"":"；请先核对原请求，不要重复生成");
  else if(value instanceof LyricRhymePreview.Result r)detail=switch(r.status()){
    case SUGGESTIONS -> String.join("\n",r.candidates().stream().map(LyricRhymePreview.Candidate::text).toList());
    case CHOOSE_REFERENCE -> "请选择希望押韵的参考句。";
    case ALREADY_RHYMES -> "原句已经押韵，可保留原句。";
    case NO_MATCH -> "暂未找到合适韵脚，原句已保留。";
    case UNKNOWN_PRONUNCIATION -> "无法可靠判断读音，请选择其他参考句。";
  };
  else detail="已取得结果，可继续指定下一步操作。";
  return new Result(label+"。","feature",List.of(new Item(action,label,detail,value==null?Map.of():Map.of("details",json.valueToTree(value)))),List.of(),action);
 }
 private static String value(Map<String,Object> args,String key,String fallback){return args.get(key) instanceof String s?s:fallback;}
 private static String required(Map<String,Object> args,String key){String s=value(args,key,null);if(s==null||s.isBlank())throw invalid("缺少 "+key);return s;}
 private static DomainException invalid(String message){return new DomainException("INVALID_AGENT_FEATURE",message);}
}
