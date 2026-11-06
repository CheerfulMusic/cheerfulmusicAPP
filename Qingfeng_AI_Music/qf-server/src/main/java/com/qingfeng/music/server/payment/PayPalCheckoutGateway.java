package com.qingfeng.music.server.payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PayPal Orders v2 adapter. Not registered as a bean until the application flow is wired and verified. */
public final class PayPalCheckoutGateway {
    public enum Environment { SANDBOX, LIVE }
    public record Created(String orderId, String approvalUrl) {}
    public record CheckoutView(Snapshot snapshot, String approvalUrl) {}
    public record Snapshot(String orderId, String reference, String orderStatus, String payeeId,
                           String captureId, String captureStatus, BigDecimal amount, String currency) {}
    public record WebhookHeaders(String authAlgo, String certUrl, String transmissionId,
                                 String transmissionSig, String transmissionTime) {}
    interface Transport { JsonNode send(String method, String path, Map<String,String> headers, String body); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Environment environment;
    private final String merchantId;
    private final String webhookId;
    private final Transport transport;

    public PayPalCheckoutGateway(Environment environment, String clientId, String secret, String merchantId, String webhookId) {
        this(environment, merchantId, webhookId, new HttpTransport(environment,clientId,secret));
    }
    PayPalCheckoutGateway(Environment environment, String merchantId, String webhookId, Transport transport) {
        if(environment==null || merchantId==null || merchantId.isBlank() || webhookId==null || webhookId.isBlank()) throw new IllegalArgumentException("Missing PayPal configuration");
        this.environment=environment; this.merchantId=merchantId; this.webhookId=webhookId; this.transport=transport;
    }
    public Created create(String reference,String title,BigDecimal amount,String currency,String returnUrl,String cancelUrl) {
        requireReference(reference);
        if(title==null || title.isBlank() || amount==null || amount.signum()<=0 || amount.scale()>2 || !List.of("GBP","USD").contains(currency)) throw new IllegalArgumentException("Invalid order");
        requireReturnUrl(returnUrl); requireReturnUrl(cancelUrl);
        var purchase=Map.of("reference_id",reference,"custom_id",reference,"description",title,
                "amount",Map.of("currency_code",currency,"value",amount.setScale(2).toPlainString()));
        var body=Map.of("intent","CAPTURE","purchase_units",List.of(purchase),"payment_source",Map.of("paypal",Map.of("experience_context",Map.of("return_url",returnUrl,"cancel_url",cancelUrl,"user_action","PAY_NOW"))));
        JsonNode order=transport.send("POST","/v2/checkout/orders",Map.of("PayPal-Request-Id",reference,"Prefer","return=representation"),JSON.writeValueAsString(body));
        String id=order.path("id").asString(); requireOrderId(id);
        String approval=approvalUrl(order);
        return new Created(id,approval);
    }
    private String approvalUrl(JsonNode order) {
        String approval="";
        for(JsonNode link:order.path("links")) if("payer-action".equals(link.path("rel").asString()) || "approve".equals(link.path("rel").asString())) { approval=link.path("href").asString(); break; }
        URI uri=URI.create(approval); String host=uri.getHost();
        String expected=environment==Environment.SANDBOX?"www.sandbox.paypal.com":"www.paypal.com";
        if(!"https".equalsIgnoreCase(uri.getScheme()) || !expected.equalsIgnoreCase(host) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) throw new IllegalStateException("Invalid PayPal approval URL");
        return approval;
    }
    public Snapshot retrieve(String orderId) { requireOrderId(orderId); return snapshot(transport.send("GET","/v2/checkout/orders/"+orderId,Map.of(),null),orderId); }
    public CheckoutView retrieveCheckout(String orderId) {
        requireOrderId(orderId);
        JsonNode order=transport.send("GET","/v2/checkout/orders/"+orderId,Map.of(),null);
        Snapshot state=snapshot(order,orderId);
        String status=state.orderStatus();
        return new CheckoutView(state, ("CREATED".equals(status)||"PAYER_ACTION_REQUIRED".equals(status)) ? approvalUrl(order) : "");
    }
    public Snapshot capture(String orderId,String requestId) {
        requireOrderId(orderId); requireReference(requestId);
        return snapshot(transport.send("POST","/v2/checkout/orders/"+orderId+"/capture",Map.of("PayPal-Request-Id",requestId,"Prefer","return=representation"),"{}"),orderId);
    }
    /** Validate signature by PayPal's verification API; no remote certificate URLs are fetched locally. */
    public boolean verifyWebhook(WebhookHeaders h,byte[] rawBody) {
        if(h==null || !valid(h.authAlgo()) || !valid(h.certUrl()) || !valid(h.transmissionId()) || !valid(h.transmissionSig()) || !valid(h.transmissionTime()) || rawBody==null || rawBody.length>262144) return false;
        JsonNode event=JSON.readTree(rawBody);
        var payload=Map.of("auth_algo",h.authAlgo(),"cert_url",h.certUrl(),"transmission_id",h.transmissionId(),"transmission_sig",h.transmissionSig(),"transmission_time",h.transmissionTime(),"webhook_id",webhookId,"webhook_event",event);
        return "SUCCESS".equals(transport.send("POST","/v1/notifications/verify-webhook-signature",Map.of(),JSON.writeValueAsString(payload)).path("verification_status").asString());
    }
    private Snapshot snapshot(JsonNode order,String expectedId) {
        if(!expectedId.equals(order.path("id").asString()) || order.path("purchase_units").size()!=1) throw new IllegalStateException("Invalid PayPal order");
        JsonNode unit=order.path("purchase_units").get(0), captures=unit.path("payments").path("captures");
        if(!merchantId.equals(unit.path("payee").path("merchant_id").asString())) throw new IllegalStateException("Unexpected PayPal merchant");
        if(captures.size()>1) throw new IllegalStateException("Multiple captures are not supported");
        JsonNode capture=captures.size()==1?captures.get(0):null;
        String currency=capture==null?unit.path("amount").path("currency_code").asString():capture.path("amount").path("currency_code").asString();
        String amount=capture==null?unit.path("amount").path("value").asString():capture.path("amount").path("value").asString();
        if(!List.of("GBP","USD").contains(currency) || !amount.matches("[0-9]{1,12}\\.[0-9]{2}")) throw new IllegalStateException("Invalid PayPal amount");
        return new Snapshot(expectedId,unit.path("custom_id").asString(),order.path("status").asString(),merchantId,capture==null?"":capture.path("id").asString(),capture==null?"":capture.path("status").asString(),new BigDecimal(amount),currency);
    }
    private static boolean valid(String s){return s!=null && !s.isBlank() && s.length()<=4096;}
    private static void requireReference(String s){if(s==null || !s.matches("[A-Za-z0-9_-]{1,38}"))throw new IllegalArgumentException("Invalid request reference");}
    private static void requireOrderId(String s){if(s==null || !s.matches("[A-Za-z0-9]{1,32}"))throw new IllegalArgumentException("Invalid PayPal order ID");}
    private static void requireReturnUrl(String s){URI u=URI.create(s);if(!"https".equalsIgnoreCase(u.getScheme()) || !"cheerfulmusic.com".equalsIgnoreCase(u.getHost()) || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443))throw new IllegalArgumentException("Invalid return URL");}

    private static final class HttpTransport implements Transport {
        private final String base, basic; private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpTransport(Environment environment,String clientId,String secret){
            if(environment==null || clientId==null || clientId.isBlank() || secret==null || secret.isBlank()) throw new IllegalArgumentException("Missing PayPal credentials");
            base=environment==Environment.SANDBOX?"https://api-m.sandbox.paypal.com":"https://api-m.paypal.com";
            basic=Base64.getEncoder().encodeToString((clientId+":"+secret).getBytes(StandardCharsets.UTF_8));
        }
        public JsonNode send(String method,String path,Map<String,String> headers,String body){
            JsonNode token=request("POST","/v1/oauth2/token",Map.of("Authorization","Basic "+basic,"Content-Type","application/x-www-form-urlencoded"),"grant_type=client_credentials");
            String access=token.path("access_token").asString(); if(access.isBlank())throw new IllegalStateException("PayPal authorization failed");
            var auth=new java.util.HashMap<>(headers);auth.put("Authorization","Bearer "+access);auth.put("Content-Type","application/json");
            return request(method,path,auth,body);
        }
        private JsonNode request(String method,String path,Map<String,String> headers,String body){
            try {
                var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(25));headers.forEach(request::header);
                request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
                var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()<200 || response.statusCode()>=300)throw new IllegalStateException("PayPal request failed: HTTP "+response.statusCode());
                return JSON.readTree(response.body());
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("PayPal request interrupted",e);}catch(IOException e){throw new IllegalStateException("PayPal request failed",e);}
        }
    }
}
