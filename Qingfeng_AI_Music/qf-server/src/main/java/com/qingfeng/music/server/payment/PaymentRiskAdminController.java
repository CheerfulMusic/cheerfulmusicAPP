package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Independent financial authorization. Deliberately absent from all Agent capability catalogs. */
@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/payment-risk")
public class PaymentRiskAdminController {
    private final PaymentRiskService risks;private final Set<String> admins;
    public PaymentRiskAdminController(PaymentRiskService risks,@Value("${qingfeng.payment.risk.admin-owner-ids:}")String owners){
        this.risks=risks;this.admins=Arrays.stream(owners.split(",")).map(String::trim).filter(v->!v.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
    private String authorize(CreatorPrincipal principal){
        if(principal==null||!admins.contains(principal.id()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal.id();
    }
    @GetMapping("/cases") public java.util.List<PaymentRiskService.RiskCase> cases(@AuthenticationPrincipal CreatorPrincipal principal){authorize(principal);return risks.listOpen(100);}
    public record Review(@NotBlank @Size(max=128)String expectedEventId,@NotBlank @Pattern(regexp="RELEASE|HOLD")String decision,@NotBlank @Size(min=10,max=1000)String reason){}
    @PostMapping("/cases/{id}/review") public PaymentRiskService.RiskCase review(@AuthenticationPrincipal CreatorPrincipal principal,@PathVariable String id,@Valid @RequestBody Review body){
        String reviewer=authorize(principal);
        if(!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效风险记录");
        try{return risks.resolveCase(id,body.expectedEventId(),body.decision(),reviewer,body.reason());}
        catch(IllegalStateException changed){throw new ResponseStatusException(HttpStatus.CONFLICT,"风险事件已更新，请重新核查");}
        catch(org.springframework.dao.EmptyResultDataAccessException missing){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效审核参数");}
    }
}
