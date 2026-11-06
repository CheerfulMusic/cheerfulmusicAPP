package com.qingfeng.music.server.web.app;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/credits")
public class CreditsController {
    private final CreditsLedger credits; private final Environment env;
    public CreditsController(CreditsLedger credits, Environment env){this.credits=credits;this.env=env;}
    public record View(long balance,boolean enabled,long musicCost) {}
    @GetMapping public View get(@AuthenticationPrincipal CreatorPrincipal creator){return new View(credits.balance(creator.id()),env.getProperty("qingfeng.credits.enabled",Boolean.class,true),env.getProperty("qingfeng.credits.music-cost",Long.class,15L));}
}
