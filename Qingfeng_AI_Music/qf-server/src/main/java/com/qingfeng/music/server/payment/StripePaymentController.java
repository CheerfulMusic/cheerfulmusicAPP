package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"mysql", "test"})
public class StripePaymentController {
    private final StripePaymentService payments;
    public StripePaymentController(StripePaymentService payments) { this.payments=payments; }
    public record OrderRequest(String packageId) {}

    @GetMapping("/api/app/stripe/catalog")
    public Map<String,Object> catalog() { return payments.catalog(); }

    @PostMapping("/api/app/stripe/orders")
    public Map<String,Object> create(@AuthenticationPrincipal CreatorPrincipal creator,@RequestBody OrderRequest request) {
        return payments.create(creator,request.packageId());
    }

    @GetMapping("/api/app/stripe/orders")
    public Map<String,Object> orders(@AuthenticationPrincipal CreatorPrincipal creator) { return payments.orders(creator); }

    @GetMapping("/api/app/stripe/orders/{id}")
    public Map<String,Object> order(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id) { return payments.order(creator,id); }

    @PostMapping("/api/app/stripe/orders/{id}/resume")
    public Map<String,Object> resume(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id) { return payments.resume(creator,id); }

    @PostMapping("/api/public/stripe/webhook")
    public Map<String,Object> webhook(@RequestHeader(value="Stripe-Signature",required=false) String signature,@RequestBody byte[] rawBody) {
        return payments.webhook(signature,rawBody);
    }
}
