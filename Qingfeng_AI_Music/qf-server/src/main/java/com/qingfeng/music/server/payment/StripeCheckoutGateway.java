package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

/** Stripe hosted Checkout adapter. Browser redirects are never treated as proof of payment. */
@Component
@Profile({"mysql", "test"})
public final class StripeCheckoutGateway {
    private static final String STRIPE_API_VERSION = "2025-03-31.basil";
    private static final String DEFAULT_SUCCESS_URL="https://cheerfulmusic.com/recharge?stripe_session_id={CHECKOUT_SESSION_ID}";
    private static final String DEFAULT_CANCEL_URL="https://cheerfulmusic.com/recharge?stripe_cancelled=1";
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile boolean accountVerified;
    private volatile String verifiedCountry = "";
    private volatile boolean verifiedChargesEnabled;
    @Value("${qingfeng.stripe.environment:sandbox}") private String environment;
    @Value("${qingfeng.stripe.account-id:}") private String accountId;
    @Value("${qingfeng.stripe.api-key:}") private String apiKey;
    @Value("${qingfeng.stripe.webhook-secret:}") private String webhookSecret;
    @Value("${qingfeng.stripe.success-url:}") private String successUrl;
    @Value("${qingfeng.stripe.cancel-url:}") private String cancelUrl;
    @Value("${qingfeng.stripe.business-country:GB}") private String businessCountry;

    @org.springframework.beans.factory.annotation.Autowired
    public StripeCheckoutGateway(ObjectMapper json) { this(json, HTTP); }
    StripeCheckoutGateway(ObjectMapper json, HttpClient http) { this.json = json; this.http = java.util.Objects.requireNonNull(http); }

    public boolean configured() {
        boolean sandbox = "sandbox".equalsIgnoreCase(environment)
            && (apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_"));
        boolean live = "live".equalsIgnoreCase(environment)
            && (apiKey.startsWith("sk_live_") || apiKey.startsWith("rk_live_"));
        return (sandbox || live) && accountId.matches("acct_[A-Za-z0-9]{8,64}")
            && webhookSecret.startsWith("whsec_") && validReturnUrl(returnUrl(true), true)
            && validReturnUrl(returnUrl(false), false);
    }

    public boolean isLive() { return "live".equalsIgnoreCase(environment); }
    public String provider() { return isLive() ? "STRIPE_LIVE" : "STRIPE_SANDBOX"; }
    public String merchantId() { return accountId; }
    public String environment() { return environment.toLowerCase(java.util.Locale.ROOT); }

    /** Bind the configured merchant id to the account authenticated by the API secret. */
    public void verifyAccount() {
        requireConfigured();
        if (accountVerified) return;
        synchronized (this) {
            if (accountVerified) return;
            JsonNode account=send("GET","/account",null,null);
            if (!accountId.equals(account.path("id").asString()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe account is not the configured merchant");
            verifiedCountry=account.path("country").asString("").trim().toUpperCase(java.util.Locale.ROOT);
            verifiedChargesEnabled=account.path("charges_enabled").asBoolean(false);
            accountVerified=true;
        }
    }

    public JsonNode createSession(String orderId, String title, BigDecimal amount, String currency) {
        return createSession(orderId,title,amount,currency,"card");
    }

    public JsonNode createSession(String orderId, String title, BigDecimal amount, String currency, String checkoutMethod) {
        requireConfigured();
        Map<String,String> fields=sessionFields(orderId,title,amount,currency,checkoutMethod);
        fields.put("success_url",returnUrl(true));
        fields.put("cancel_url",returnUrl(false));
        verifyNewPurchase(checkoutMethod,currency);
        return post("/checkout/sessions", fields, "checkout-session:" + orderId);
    }

    /** Reject a definitely unsupported purchase before the caller reserves a local order. */
    public void verifyNewPurchase(String checkoutMethod,String currency) {
        if(!("card".equals(checkoutMethod)||"wechat_pay".equals(checkoutMethod))
            || !("USD".equals(currency)||("CNY".equals(currency)||"GBP".equals(currency))&&"wechat_pay".equals(checkoutMethod)))
            throw new IllegalArgumentException("Unsupported Stripe checkout method or currency");
        verifyAccount();
        // Receiving an existing payment/return remains possible when new charges are paused.
        if(isLive()&&!verifiedChargesEnabled)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe new payments are currently unavailable");
        if("wechat_pay".equals(checkoutMethod)) {
            if(!verifiedCountry.equals(businessCountry==null?"":businessCountry.trim().toUpperCase(java.util.Locale.ROOT))
                || !("CNY".equals(currency)&&java.util.Set.of("GB","US").contains(verifiedCountry)
                     || "GBP".equals(currency)&&"GB".equals(verifiedCountry)
                     || "USD".equals(currency)&&"US".equals(verifiedCountry)))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WeChat Pay account country or currency is not supported");
        }
    }

    /** Deterministic request from the immutable order snapshot, not today's price or browser input. */
    static Map<String,String> sessionFields(String orderId,String title,BigDecimal amount,String currency,String checkoutMethod) {
        if(!("card".equals(checkoutMethod)||"wechat_pay".equals(checkoutMethod))
            || !("USD".equals(currency)||("CNY".equals(currency)||"GBP".equals(currency))&&"wechat_pay".equals(checkoutMethod)))
            throw new IllegalArgumentException("Unsupported Stripe checkout method or currency");
        if(amount==null||amount.signum()<=0)throw new IllegalArgumentException("Invalid Stripe payment amount");
        long minor = amount.movePointRight(2).longValueExact();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mode", "payment");
        fields.put("payment_method_types[0]", checkoutMethod);
        if("wechat_pay".equals(checkoutMethod))fields.put("payment_method_options[wechat_pay][client]","web");
        fields.put("line_items[0][quantity]", "1");
        fields.put("line_items[0][price_data][currency]", currency.toLowerCase(java.util.Locale.ROOT));
        fields.put("line_items[0][price_data][unit_amount]", Long.toString(minor));
        fields.put("line_items[0][price_data][product_data][name]", title);
        fields.put("client_reference_id", orderId);
        fields.put("metadata[order_id]", orderId);
        fields.put("payment_intent_data[metadata][order_id]", orderId);
        return fields;
    }

    public JsonNode expireSession(String sessionId) {
        verifyAccount();
        return post("/checkout/sessions/" + safeId(sessionId) + "/expire", Map.of(), "expire-session:" + sessionId);
    }

    public JsonNode retrieveSession(String sessionId) {
        verifyAccount();
        return get("/checkout/sessions/" + safeId(sessionId), "expand%5B%5D=payment_intent");
    }

    /** One bounded GET page. A caller must not infer absence until has_more is false. */
    public JsonNode sessionsCreatedBetween(InstantRange range,String startingAfter) {
        if(range==null||range.from()<0||range.until()<range.from()||range.until()-range.from()>86400)
            throw new IllegalArgumentException("Invalid Checkout reconciliation window");
        verifyAccount();
        String query="limit=100&created%5Bgte%5D="+range.from()+"&created%5Blte%5D="+range.until();
        if(startingAfter!=null&&!startingAfter.isBlank())query+="&starting_after="+safeId(startingAfter);
        return get("/checkout/sessions",query);
    }
    public record InstantRange(long from,long until) {}

    public JsonNode retrieveRefund(String refundId) { verifyAccount(); return get("/refunds/" + safeId(refundId), null); }
    public JsonNode retrieveDispute(String disputeId) {
        verifyAccount();
        // balance_transactions is already an array of objects, not an expandable list.
        return get("/disputes/" + safeId(disputeId), "expand%5B%5D=charge");
    }
    public JsonNode retrieveCharge(String chargeId) {
        verifyAccount();
        return get("/charges/" + safeId(chargeId), null);
    }
    public JsonNode retrieveBalanceTransaction(String transactionId) {
        verifyAccount();
        return get("/balance_transactions/" + safeId(transactionId), null);
    }
    /** Deliberately bounded: a caller must reject has_more=true, never treat a first page as complete. */
    public JsonNode refundsForCharge(String chargeId) {
        verifyAccount();
        return get("/refunds", "limit=100&charge=" + safeId(chargeId));
    }
    /** Includes every dispute state; only a complete single-dispute result is admitted by principal proof. */
    public JsonNode disputesForCharge(String chargeId) {
        verifyAccount();
        return get("/disputes", "limit=100&charge=" + safeId(chargeId));
    }

    /** Stripe's official SDK verifies the signature and timestamp against the exact request body. */
    public JsonNode verifyWebhook(byte[] rawBody, String signature) {
        requireConfigured();
        if (rawBody == null || rawBody.length == 0 || rawBody.length > 1_000_000 || signature == null || signature.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook request");
        try {
            String payload = new String(rawBody, StandardCharsets.UTF_8);
            Event verified = Webhook.constructEvent(payload, signature, webhookSecret);
            JsonNode event = json.readTree(payload);
            String eventId = event.path("id").asString();
            String eventType = event.path("type").asString();
            boolean live = event.path("livemode").asBoolean(false);
            String eventAccount = event.path("account").asString("");
            if (verified == null || !eventId.matches("evt_[A-Za-z0-9]{8,128}")
                || eventType.isBlank() || live != isLive()
                || (!eventAccount.isBlank() && !accountId.equals(eventAccount)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stripe event account or mode mismatch");
            return event;
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Stripe webhook signature");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe webhook payload");
        }
    }

    private JsonNode get(String path, String query) {
        requireConfigured();
        return send("GET", path + (query == null ? "" : "?" + query), null, null);
    }

    private JsonNode post(String path, Map<String, String> fields, String idempotencyKey) {
        String form = fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        return send("POST", path, form, idempotencyKey);
    }

    private JsonNode send(String method, String path, String form, String idempotencyKey) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1" + path))
                .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json").header("Stripe-Version",STRIPE_API_VERSION);
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            if (form == null) request.GET();
            else request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new StripeRemoteException(response.statusCode());
            return json.readTree(response.body());
        } catch (StripeRemoteException e) {
            throw e;
        } catch (Exception e) {
            // Do not log headers, request body, provider response or exception message (may contain user data).
            throw new StripeRemoteException(0);
        }
    }

    private void requireConfigured() {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe is not configured for this environment");
    }

    private static String safeId(String id) {
        if (id == null || !RESOURCE_ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe resource id");
        return id;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String returnUrl(boolean success) {
        String configured=success?successUrl:cancelUrl;
        return configured==null||configured.isBlank()?(success?DEFAULT_SUCCESS_URL:DEFAULT_CANCEL_URL):configured;
    }

    private boolean validReturnUrl(String value, boolean success) {
        if (value == null || value.isBlank() || value.length() > 2048) return false;
        try {
            URI uri = URI.create(value.replace("{CHECKOUT_SESSION_ID}", "session-placeholder"));
            boolean placeholder = value.contains("{CHECKOUT_SESSION_ID}");
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean productionHost=host.equals("cheerfulmusic.com")||host.endsWith(".cheerfulmusic.com");
            boolean localSandbox="sandbox".equalsIgnoreCase(environment)
                && (host.equals("localhost")||host.equals("127.0.0.1"))
                && ("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()));
            return ("https".equalsIgnoreCase(uri.getScheme())&&productionHost||localSandbox)
                && (!success || placeholder) && (success || !placeholder);
        } catch (RuntimeException e) { return false; }
    }

    public static final class StripeRemoteException extends RuntimeException {
        private final int statusCode;
        StripeRemoteException(int statusCode) { super("Stripe API request failed"); this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }
}
