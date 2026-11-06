package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
public class PayPalPaymentController {
    private final PayPalPaymentService service;
    public PayPalPaymentController(PayPalPaymentService service){this.service=service;}
    public record OrderRequest(String packageId){}
    @GetMapping("/api/app/paypal/catalog") public Map<String,Object> catalog(){return service.catalog();}
    @GetMapping("/api/app/paypal/sdk-config") public Map<String,Object> sdkConfig(){return service.sdkConfig();}
    @PostMapping("/api/app/paypal/orders") public Map<String,Object> create(@AuthenticationPrincipal CreatorPrincipal principal,@RequestBody OrderRequest req){return service.create(principal.id(),req.packageId());}
    @GetMapping("/api/app/paypal/orders") public Map<String,Object> orders(@AuthenticationPrincipal CreatorPrincipal principal){return service.orders(principal.id());}
    @GetMapping("/api/app/paypal/orders/{id}") public Map<String,Object> order(@AuthenticationPrincipal CreatorPrincipal principal,@PathVariable String id){return service.order(principal.id(),id);}
    @PostMapping("/api/app/paypal/orders/{id}/resume") public Map<String,Object> resume(@AuthenticationPrincipal CreatorPrincipal principal,@PathVariable String id){return service.resume(principal.id(),id);}
    @PostMapping("/api/app/paypal/orders/{id}/capture") public Map<String,Object> capture(@AuthenticationPrincipal CreatorPrincipal principal,@PathVariable String id){return service.capture(principal.id(),id);}
    @PostMapping("/api/public/paypal/webhook") public void webhook(@RequestHeader(value="PAYPAL-AUTH-ALGO",required=false) String algo,@RequestHeader(value="PAYPAL-CERT-URL",required=false) String cert,@RequestHeader(value="PAYPAL-TRANSMISSION-ID",required=false) String transmission,@RequestHeader(value="PAYPAL-TRANSMISSION-SIG",required=false) String signature,@RequestHeader(value="PAYPAL-TRANSMISSION-TIME",required=false) String time,@RequestBody byte[] body){service.webhook(new PayPalCheckoutGateway.WebhookHeaders(algo,cert,transmission,signature,time),body);}
}
