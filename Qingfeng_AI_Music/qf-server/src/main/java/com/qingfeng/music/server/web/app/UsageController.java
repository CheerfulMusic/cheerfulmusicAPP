package com.qingfeng.music.server.web.app;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.usage.UsageBudget.Kind;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/usage")
public class UsageController {
    private final JdbcUsageBudget budget;
    public UsageController(JdbcUsageBudget budget) {this.budget=budget;}
    @GetMapping public Map<String,JdbcUsageBudget.Allowance> get(@AuthenticationPrincipal CreatorPrincipal creator) {
        return Map.of("music",budget.allowance(creator.id(),Kind.MUSIC),"lyrics",budget.allowance(creator.id(),Kind.LYRICS),"timing",budget.allowance(creator.id(),Kind.TIMING),"cover",budget.allowance(creator.id(),Kind.COVER));
    }
}
