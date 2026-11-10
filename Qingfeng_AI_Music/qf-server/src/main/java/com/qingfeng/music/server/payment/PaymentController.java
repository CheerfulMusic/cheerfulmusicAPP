package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** HTTP boundary; payment decisions are delegated to the application service. */
@RestController
@Profile({"mysql", "test"})
public class PaymentController {
    private final PaymentService payments;
    public PaymentController(PaymentService payments) { this.payments = payments; }
    public record OrderRequest(String packageId) {}
    @GetMapping("/api/app/payments/catalog")
    public Map<String,Object> catalog() { return payments.catalog(); }
    @PostMapping("/api/app/payments/orders")
    public Map<String,Object> create(@AuthenticationPrincipal CreatorPrincipal creator, @RequestBody OrderRequest request) {
        return payments.create(creator, new PaymentService.OrderRequest(request.packageId()));
    }
    public record RecoveryRequest(String providerLinkId) {}
    @PostMapping("/api/app/payments/orders/{id}/recover")
    public Map<String,Object> recover(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestBody RecoveryRequest request) {
        return payments.recover(creator,id,request.providerLinkId());
    }
    @GetMapping("/api/app/payments/orders/{id}")
    public Map<String,Object> order(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String id) { return payments.order(creator, id); }
    @GetMapping("/api/app/payments/orders")
    public Map<String,Object> orders(@AuthenticationPrincipal CreatorPrincipal creator) { return payments.orders(creator); }
    @PostMapping("/api/public/payments/airwallex/webhook")
    public void webhook(@RequestHeader(value="x-timestamp",required=false) String timestamp,
                        @RequestHeader(value="x-signature",required=false) String signature, @RequestBody byte[] body) {
        payments.webhook(timestamp, signature, body);
    }
}
