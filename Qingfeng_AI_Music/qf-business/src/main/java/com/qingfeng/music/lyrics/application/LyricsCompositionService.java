package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 外部请求不占用数据库事务；失败请求保留幂等键和额度，只有用户明确新建请求才再次调用模型。 */
public final class LyricsCompositionService {
    private static final System.Logger LOG=System.getLogger(LyricsCompositionService.class.getName());
    public static final int MAX_REWRITE_SOURCE_LENGTH = 12_000;
    private static final String REWRITE_ALL_INSTRUCTION = """
        【整首重写模式】
        previousLyrics 是编辑区当前歌词。保持其中的人物、事实、视角和主题，用新的切入点和表达重新写整首歌词。
        逐段改写实际歌词，不能照抄原稿，也不能只改标题、段落标签、标点或换行。遵守下文的补充要求和结构约束。
        """;
    private static final Pattern SECTION_LINE = Pattern.compile("(?m)^\\h*\\[[^]\\r\\n]+]\\h*$");
    private static final Pattern LYRIC_FORMATTING = Pattern.compile("[^\\p{L}\\p{N}\\p{M}]+");
    private final WorkApi works;private final LyricsApi lyrics;private final LyricsRepository revisions;
    private final LyricsWriter writer;private final LyricsWriter customWriter;private final CompositionRequests requests;private final boolean enabled;
    private com.qingfeng.music.settings.MusicSettings settings;
    private com.qingfeng.music.settings.CreativePlan.Repository plans;
    private PhotoLyricsWriter photoWriter;
    public enum Mode { STANDARD, CUSTOM, PHOTO }
    public record Command(long version, String language, String instructions, String baseRevisionId,
                          LyricsStructure structure, Mode mode, String rewriteSource) {}
    @FunctionalInterface
    public interface QueuedComposition {
        LyricsApi.Revision compose(String owner, String work, String key, Command command, List<byte[]> photos);
    }
    private QueuedComposition queuedComposition;

    public LyricsCompositionService withQueue(QueuedComposition queue) {
        queuedComposition = queue;
        return this;
    }
    public boolean usesQueue() { return queuedComposition != null; }
    public LyricsApi.Revision result(String owner, String work, String revision) {
        return lyrics.get(owner, work, revision);
    }

    public Command customCommand(long version, String language, String instructions, String baseRevisionId,
                                 String template, boolean chorusFirst, List<String> sections,
                                 boolean rewriteAll, String sourceLyrics) {
        if (rewriteAll && (sourceLyrics == null || sourceLyrics.length() > MAX_REWRITE_SOURCE_LENGTH || comparableLyrics(sourceLyrics).isEmpty()))
            throw new DomainException("INVALID_LYRICS_REQUEST", "请填写要重写的歌词，最多12000字。");
        return new Command(version, language, instructions, baseRevisionId, new LyricsStructure(template, chorusFirst, sections),
            Mode.CUSTOM, rewriteAll ? sourceLyrics : null);
    }

    public Command photoCommand(long version, String language) {
        return new Command(version, language, "", null, PhotoLyricsWriter.DEFAULT_STRUCTURE, Mode.PHOTO, null);
    }

    public LyricsCompositionService withPhotoWriter(PhotoLyricsWriter value){photoWriter=value;return this;}
    public LyricsCompositionService withCreativePlans(com.qingfeng.music.settings.MusicSettings settings,com.qingfeng.music.settings.CreativePlan.Repository plans){this.settings=settings;this.plans=plans;return this;}
    public LyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsRepository revisions,LyricsWriter writer,CompositionRequests requests,boolean enabled){
        this(works,lyrics,revisions,writer,writer,requests,enabled);
    }
    public LyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsRepository revisions,LyricsWriter writer,
                                   LyricsWriter customWriter,CompositionRequests requests,boolean enabled){
        this.works=works;this.lyrics=lyrics;this.revisions=revisions;this.writer=writer;this.customWriter=customWriter;this.requests=requests;this.enabled=enabled;
    }
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language){
        return compose(owner,work,expectedVersion,key,language,"",null);
    }
    public record RequestState(String status,String revisionId,String errorCode){}
    public RequestState requestState(String owner,String work,String key){
        works.get(owner,work);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_LYRICS_REQUEST","歌词生成参数无效，请刷新后重试");
        return requests.find(work,key).map(e->new RequestState(e.status(),e.revisionId(),e.errorCode())).orElseGet(()->new RequestState("NOT_FOUND",null,null));
    }
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language,String instructions,String baseRevisionId){
        return compose(owner,work,expectedVersion,key,language,instructions,baseRevisionId,null,false);
    }
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language,String instructions,String baseRevisionId,String templateId,boolean chorusFirst){
        return compose(owner, work, key, new Command(expectedVersion, language, instructions, baseRevisionId,
            new LyricsStructure(templateId, chorusFirst), Mode.STANDARD, null), List.of());
    }
    public LyricsApi.Revision composeCustom(String owner,String work,long expectedVersion,String key,String language,
                                             String instructions,String baseRevisionId,String templateId,boolean chorusFirst){
        return composeCustom(owner,work,expectedVersion,key,language,instructions,baseRevisionId,templateId,chorusFirst,null);
    }
    public LyricsApi.Revision composeCustom(String owner,String work,long expectedVersion,String key,String language,
                                             String instructions,String baseRevisionId,String templateId,boolean chorusFirst,List<String> customSections){
        return composeCustom(owner,work,expectedVersion,key,language,instructions,baseRevisionId,templateId,chorusFirst,customSections,false,null);
    }
    public LyricsApi.Revision composeCustom(String owner,String work,long expectedVersion,String key,String language,
                                             String instructions,String baseRevisionId,String templateId,boolean chorusFirst,
                                             List<String> customSections,boolean rewriteAll,String sourceLyrics){
        return compose(owner, work, key, customCommand(expectedVersion, language, instructions, baseRevisionId,
            templateId, chorusFirst, customSections, rewriteAll, sourceLyrics), List.of());
    }
    public LyricsApi.Revision composePhoto(String owner,String work,long expectedVersion,String key,String language,List<byte[]> photos){
        return compose(owner, work, key, photoCommand(expectedVersion, language), photos);
    }

    private LyricsApi.Revision compose(String owner, String work, String key, Command command, List<byte[]> photos) {
        if (queuedComposition != null) return queuedComposition.compose(owner, work, key, command, photos);
        return run(owner, work, key, command, photos, false, false);
    }

    public void reserveQueued(String owner, String work, String key, Command command, List<byte[]> photos) {
        run(owner, work, key, command, photos, true, false);
    }

    public LyricsApi.Revision executeQueued(String owner, String work, String key, Command command, List<byte[]> photos) {
        return run(owner, work, key, command, photos, false, true);
    }

    private LyricsApi.Revision run(String owner, String work, String key, Command command, List<byte[]> photos,
                                   boolean queueOnly, boolean fromQueue) {
        long expectedVersion = command.version();
        String language = command.language();
        String instructions = command.instructions();
        String baseRevisionId = command.baseRevisionId();
        String rewriteSource = command.rewriteSource();
        LyricsStructure structure = command.structure();
        LyricsWriter selectedWriter = command.mode() == Mode.CUSTOM ? customWriter : writer;
        if ((command.mode() == Mode.PHOTO) != !photos.isEmpty())
            throw new DomainException("INVALID_LYRICS_REQUEST", "请选择 1–5 张照片。");
        var snapshot=works.get(owner,work);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}")||language==null||!java.util.Set.of("zh-CN","en","mixed").contains(language))throw new DomainException("INVALID_LYRICS_REQUEST","歌词生成参数无效，请刷新后重试");
        String requirements=instructions==null?"":instructions.trim();
        if(requirements.length()>1000)throw new DomainException("INVALID_LYRICS_REQUEST","修改要求不能超过1000字");
        String structureInstruction=structure.instruction();
        boolean photoRequest=!photos.isEmpty();
        // A server-side photo default must not change the fingerprint of an existing request.
        String material=expectedVersion+"\n"+language+"\n"+requirements+"\n"+(baseRevisionId==null?"":baseRevisionId)+(photoRequest?"":structureInstruction);
        if(structure.customSections()!=null)material+="\n"+structure.templateId();
        if(rewriteSource!=null)material+="\nrewrite-all:"+fingerprint(rewriteSource);
        if(photos.size()==1) {
            // Preserve the fingerprint used by existing single-photo requests.
            material+="\nphoto:"+digest(photos.get(0));
        } else if(photoRequest) {
            material+=photos.stream().map(this::digest).collect(Collectors.joining("\n","\nphotos:\n",""));
        }
        String hash=fingerprint(material);
        var prior=requests.find(work,key);
        if(prior.isPresent()){
            if(prior.get().requestHash()!=null&&!prior.get().requestHash().equals(hash))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于其他创作要求，请刷新版本列表后重试");
            if(prior.get().status().equals("SUCCEEDED"))return lyrics.get(owner,work,prior.get().revisionId());
            if(prior.get().status().equals("FAILED"))throw new DomainException("LYRICS_REQUEST_FAILED","上次歌词编写已结束，可以重新生成；已有内容仍会保留。");
            if (!fromQueue || !prior.get().status().equals("QUEUED")) throw pending();
        } else if (fromQueue) {
            throw pending();
        }
        if(snapshot.version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
        if(photoRequest&&photoWriter==null)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","照片写歌暂不可用，请稍后再试。");
        if(!photoRequest&&(!enabled||!selectedWriter.isConfigured()))throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词生成暂不可用，请稍后再试或手动填写");
        var photoPlan=!photoRequest&&plans!=null?plans.find(work,com.qingfeng.music.settings.CreativePlan.sourceHash(snapshot))
            .filter(plan->!plan.photoSummary().isBlank()).orElse(null):null;
        String baseLyrics=baseRevisionId==null?"":lyrics.get(owner,work,baseRevisionId).lyrics();
        String previous=rewriteSource==null?baseLyrics:rewriteSource;
        if(!photoRequest&&photoPlan==null&&previous.isBlank()&&snapshot.story().isBlank()&&snapshot.message().isBlank())throw new DomainException("INVALID_LYRICS_REQUEST","请先在故事中填写一段回忆或祝福，再生成歌词");
        if(structure.customSections()!=null)revisions.requireCustomSectionsStorage();
        if (queueOnly) {
            if (!requests.claimQueued(owner, work, key, hash)) throw pending();
            return null;
        }
        if (fromQueue ? !requests.startQueued(work, key) : !requests.claim(owner, work, key, hash)) throw pending();
        try(var ignored=LyricsRequestBudget.enter(Duration.ofSeconds(110))){
            var direction=settings==null?null:settings.resolve(owner,snapshot);
            var writingDirection=new LyricsWriter.Direction(direction==null?snapshot.style():direction.style(),direction==null?"":direction.negativeTags(),snapshot.vocalGender());
            String context=photoPlan==null?"":"\n【照片画面素材，仅用于创作】\n"+photoPlan.photoSummary();
            String writingRequirements=(rewriteSource==null?"":REWRITE_ALL_INSTRUCTION)+requirements+context+structureInstruction;
            var draft = photoRequest
                ? photoWriter.writeValidated(snapshot, language, photos, writingDirection, (provider, candidate) ->
                    validateDraft(owner, snapshot, language, writingRequirements, previous, structure, candidate, provider::repairSections))
                : selectedWriter.writeValidated(snapshot, language, writingRequirements, previous, writingDirection, (provider, candidate) ->
                    validateDraft(owner, snapshot, language, writingRequirements, previous, structure, candidate, provider::repairSections));
            String completed = draft.lyrics();
            if(rewriteSource!=null){
                String rewritten=comparableLyrics(completed);
                if(rewritten.isEmpty()||rewritten.equals(comparableLyrics(rewriteSource))||rewritten.equals(comparableLyrics(baseLyrics)))
                    throw new DomainException("LYRICS_REWRITE_UNCHANGED","这次重写没有生成新的歌词，请重试。");
            }
            var revision=new LyricsApi.Revision(UUID.randomUUID().toString(),work,expectedVersion,draft.title().trim(),completed,
                draft.source()==null?selectedWriter.source():draft.source(),false,Instant.now(),language,structure.templateId(),structure.chorusFirst(),structure.customSections());
            requests.completeWithRevision(work,key,revision.id(),()->{
                revisions.insert(revision);
                var plan=photoPlan==null?draft.plan():photoPlan;
                if(plans!=null&&plan!=null)plans.save(work,revision.id(),com.qingfeng.music.settings.CreativePlan.sourceHash(snapshot),plan);
            });return lyrics.get(owner,work,revision.id());
        }catch(RuntimeException error){
            String code=error instanceof DomainException domain?domain.code():"LYRICS_INTERNAL_ERROR";
            if(outcomeUnknown(code))requests.unknown(work,key,code);
            else requests.failed(work,key,code);
            LOG.log(System.Logger.Level.WARNING,"Lyrics request failed: work={0}, request={1}, code={2}, type={3}",work,key,code,error.getClass().getSimpleName());
            throw error;
        }
    }

    private static DomainException pending() {
        return new DomainException("LYRICS_REQUEST_PENDING", "这次歌词请求已处理或仍在确认中，请先刷新歌词版本，避免重复提交");
    }

    private LyricsWriter.Draft validateDraft(String owner, WorkApi.Work work, String language, String instructions,
                                            String previous, LyricsStructure structure, LyricsWriter.Draft draft,
                                            Function<LyricsWriter.SectionRepair, Map<Integer, String>> repair) {
        requireVersion(owner, work.id(), work.version());
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length() > 80 || draft.lyrics() == null)
            throw invalidDraft();
        String normalized = LyricsStructure.normalizeHeaders(LyricsFormatting.unwrap(draft.lyrics()));
        if (normalized.length() > 5000 || normalized.startsWith("{") || LyricsFormatting.hasFence(normalized)
                || comparableLyrics(normalized).isEmpty()) throw invalidDraft();
        var prepared = structure.prepare(normalized);
        Map<Integer, String> replacements = Map.of();
        if (!prepared.missing().isEmpty()) {
            var candidate = new LyricsWriter.Draft(draft.title(), normalized, draft.plan(), draft.source());
            replacements = repair.apply(new LyricsWriter.SectionRepair(work, language, instructions, previous, candidate, prepared.missing()));
            requireVersion(owner, work.id(), work.version());
        }
        String completed = prepared.complete(replacements);
        if (completed.length() > 5000) throw invalidDraft();
        structure.validate(completed);
        return new LyricsWriter.Draft(draft.title(), completed, draft.plan(), draft.source());
    }

    private void requireVersion(String owner,String work,long expectedVersion){
        if(works.get(owner,work).version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
    }
    private static DomainException invalidDraft(){
        return new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");
    }
    private static String comparableLyrics(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        String body = SECTION_LINE.matcher(normalized).replaceAll("");
        return LYRIC_FORMATTING.matcher(body.toLowerCase(Locale.ROOT)).replaceAll("");
    }
    public static boolean outcomeUnknown(String code){
        return switch(code){
            case "LYRICS_PROVIDER_TIMEOUT", "LYRICS_PROVIDER_NETWORK_ERROR", "LYRICS_REQUEST_INTERRUPTED" -> true;
            default -> false;
        };
    }
    private String fingerprint(String value){
        return digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private String digest(byte[] value){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
