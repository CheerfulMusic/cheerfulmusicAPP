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
    public CreditsController(CreditsLedger credits, CreditsPricing pricing, RewriteCharges rewrites){this.credits=credits;this.pricing=pricing;this.rewrites=rewrites;}
    /** 余额与价格都由服务端下发；前端不得硬编码积分价格，改价不需要重新发布前端。 */
    public record View(long balance,boolean enabled,long musicCost,long lyricsCost,long rewriteCost,long rewriteFreeRemaining) {}
    @GetMapping public View get(@AuthenticationPrincipal CreatorPrincipal creator){
        return new View(credits.balance(creator.id()),pricing.enabled(),pricing.cost(CreditsPricing.Kind.MUSIC),pricing.cost(CreditsPricing.Kind.LYRICS),pricing.cost(CreditsPricing.Kind.REWRITE),rewrites.freeRemaining(creator.id()));
    }
}
