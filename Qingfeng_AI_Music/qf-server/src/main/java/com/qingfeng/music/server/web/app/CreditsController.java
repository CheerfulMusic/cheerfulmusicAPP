package com.qingfeng.music.server.web.app;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/credits")
public class CreditsController {
    private final CreditsLedger credits; private final CreditsPricing pricing; private final RewriteCharges rewrites;
    private final com.qingfeng.music.credits.LyricFreeAllowance lyricFreeAllowance;
    public CreditsController(CreditsLedger credits, CreditsPricing pricing, RewriteCharges rewrites,com.qingfeng.music.credits.LyricFreeAllowance lyricFreeAllowance){this.credits=credits;this.pricing=pricing;this.rewrites=rewrites;this.lyricFreeAllowance=lyricFreeAllowance;}
    /** 余额与价格都由服务端下发；前端不得硬编码积分价格，改价不需要重新发布前端。 */
    public record View(long balance,boolean enabled,long musicCost,long lyricsCost,long lyricsNextCost,long rewriteCost,long rewriteFreeRemaining,long downloadCost) {}
    @GetMapping public View get(@AuthenticationPrincipal CreatorPrincipal creator,@RequestParam(required=false) String workId){
        long lyricCost=pricing.cost(CreditsPricing.Kind.LYRICS);
        long nextCost=!pricing.enabled()||workId==null||workId.isBlank()||lyricFreeAllowance.freeAvailable(creator.id(),workId)?0:lyricCost;
        return new View(credits.balance(creator.id()),pricing.enabled(),pricing.cost(CreditsPricing.Kind.MUSIC),lyricCost,nextCost,
            pricing.cost(CreditsPricing.Kind.REWRITE),rewrites.freeRemaining(creator.id()),com.qingfeng.music.server.download.DownloadEntitlements.PRICE);
    }
}
