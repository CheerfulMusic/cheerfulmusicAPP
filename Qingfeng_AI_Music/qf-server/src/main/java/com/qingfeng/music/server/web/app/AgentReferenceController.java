package com.qingfeng.music.server.web.app;

import com.qingfeng.music.agent.api.ReferenceAnalysis;
import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.agent.application.ReferenceAnalysisService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参考音乐的语义化分析。只接收浏览器算出的数值特征与文字，音频字节永远不会到这里。
 * 需要创作者身份（/api/app/** 统一要求），未登录请走前端本地规则降级。
 */
@RestController
@Profile({"mysql", "test"})
@RequestMapping("/api/app/agent")
public class AgentReferenceController {
    private final ReferenceAnalysisService service;

    public AgentReferenceController(ReferenceAnalysisService service) { this.service = service; }

    public record Features(
        @DecimalMin("0") @DecimalMax("300") double bpm,
        @DecimalMin("0") @DecimalMax("1") double bpmConfidence,
        @DecimalMin("0") @DecimalMax("1") double rms,
        @DecimalMin("0") @DecimalMax("1") double peak,
        @DecimalMin("0") @DecimalMax("1") double dynamicRange,
        @DecimalMin("0") @DecimalMax("1") double low,
        @DecimalMin("0") @DecimalMax("1") double mid,
        @DecimalMin("0") @DecimalMax("1") double high,
        @DecimalMin("0") @DecimalMax("1") double brightness,
        @DecimalMin("0") @DecimalMax("1") double noisiness,
        @DecimalMin("0") @DecimalMax("7200") double duration,
        @DecimalMin("0") @DecimalMax("180") double analyzedSeconds) {}

    public record AnalysisRequest(
        @Size(max = 400) String text,
        @Size(max = 160) String fileName,
        @DecimalMin("0") @DecimalMax("7200") double duration,
        @NotNull @Pattern(regexp = "zh-CN|en") String locale,
        @NotNull @Valid Features features) {}

    public record AnalysisResponse(String style, String mood, double tempo, boolean tempoVerified,
                                   java.util.List<String> inferredInstrumentation,
                                   java.util.List<String> negativeTags, String summary, String source) {}

    @PostMapping("/reference")
    public AnalysisResponse reference(@AuthenticationPrincipal CreatorPrincipal creator, @Valid @RequestBody AnalysisRequest request) {
        Features features = request.features();
        ReferenceAnalysis analysis = service.analyse(creator.id(), new ReferenceAnalyst.Request(
            request.text(), request.fileName(), request.duration(), request.locale(),
            new ReferenceAnalyst.Features(features.bpm(), features.bpmConfidence(), features.rms(), features.peak(),
                features.dynamicRange(), features.low(), features.mid(), features.high(), features.brightness(),
                features.noisiness(), features.duration(), features.analyzedSeconds())));
        return new AnalysisResponse(analysis.style(), analysis.mood(), analysis.tempo(), analysis.tempoVerified(),
            analysis.inferredInstrumentation(), analysis.negativeTags(), analysis.summary(), analysis.source());
    }
}
