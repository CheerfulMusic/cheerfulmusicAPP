package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real gateway/form code with an injected HTTP client. No external connection or funds. */
class StripeWalletGatewayTest {
    private static final String ACCOUNT="acct_walletoffline1234";
    record Fixture(StripeCheckoutGateway gateway,List<HttpRequest> requests) {}
    @SuppressWarnings("unchecked")
    private Fixture fixture(String actualCountry,String configuredCountry,boolean live,boolean chargesEnabled,String actualAccount) throws Exception {
        var http=mock(HttpClient.class);var requests=new ArrayList<HttpRequest>();var json=new ObjectMapper();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);requests.add(request);
            assertEquals("https",request.uri().getScheme());assertEquals("api.stripe.com",request.uri().getHost());
            var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
            String body=request.uri().getPath().equals("/v1/account")
                ?json.writeValueAsString(Map.of("id",actualAccount,"country",actualCountry,"charges_enabled",chargesEnabled))
                :request.method().equals("POST")?"{\"id\":\"cs_test_wallet_fixture\",\"url\":\"https://checkout.stripe.com/c/pay/wallet_fixture\"}":"{\"id\":\"re_wallet_fixture\",\"status\":\"failed\"}";
            when(response.body()).thenReturn(body);return response;
        });
        var gateway=new StripeCheckoutGateway(json,http);
        ReflectionTestUtils.setField(gateway,"environment",live?"live":"sandbox");ReflectionTestUtils.setField(gateway,"accountId",ACCOUNT);
        ReflectionTestUtils.setField(gateway,"apiKey",live?"sk_live_offline_fixture":"sk_test_offline_fixture");
        ReflectionTestUtils.setField(gateway,"webhookSecret","whsec_offline_fixture");ReflectionTestUtils.setField(gateway,"businessCountry",configuredCountry);
        return new Fixture(gateway,requests);
    }
    private Fixture fixture(String country) throws Exception {return fixture(country,country,false,true,ACCOUNT);}
    private static Map<String,String> fields(HttpRequest request){
        var result=new CompletableFuture<byte[]>();var bytes=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer value){byte[] part=new byte[value.remaining()];value.get(part);bytes.writeBytes(part);}
            public void onError(Throwable failure){result.completeExceptionally(failure);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        });
        var values=new LinkedHashMap<String,String>();
        for(String pair:new String(result.join(),StandardCharsets.UTF_8).split("&")){
            var parts=pair.split("=",2);values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }return values;
    }
    @Test void existingCardCheckoutAlsoSupportsHostedApplePayWithoutInventingAnApplePaymentMethod() throws Exception {
        var f=fixture("GB");f.gateway().createSession("order-card","100 Credits",new BigDecimal("9.90"),"USD");
        var request=f.requests().get(1);var form=fields(request);assertEquals("card",form.get("payment_method_types[0]"));assertEquals("usd",form.get("line_items[0][price_data][currency]"));assertEquals("990",form.get("line_items[0][price_data][unit_amount]"));
        assertFalse(form.containsKey("payment_method_types[1]"));assertFalse(form.containsValue("apple_pay"));assertEquals("payment",form.get("mode"));assertTrue(form.get("success_url").contains("{CHECKOUT_SESSION_ID}"));assertNotNull(form.get("cancel_url"));assertEquals("checkout-session:order-card",request.headers().firstValue("Idempotency-Key").orElseThrow());
    }
    @Test void ukWechatCreatesARealCnyWebCheckoutWithExactMinorUnitsAndOrderMetadata() throws Exception {
        var f=fixture("GB");f.gateway().createSession("order-wechat","100 Credits",new BigDecimal("68.80"),"CNY","wechat_pay");
        var form=fields(f.requests().get(1));assertEquals("wechat_pay",form.get("payment_method_types[0]"));assertEquals("web",form.get("payment_method_options[wechat_pay][client]"));assertEquals("cny",form.get("line_items[0][price_data][currency]"));assertEquals("6880",form.get("line_items[0][price_data][unit_amount]"));assertEquals("order-wechat",form.get("metadata[order_id]"));assertEquals("order-wechat",form.get("payment_intent_data[metadata][order_id]"));assertEquals("order-wechat",form.get("client_reference_id"));
    }
    @Test void ukWechatUsdCannotFallBackToCardOrSendAnyCreateRequest() throws Exception {
        var f=fixture("GB");var error=assertThrows(ResponseStatusException.class,()->f.gateway().createSession("order","Credits",new BigDecimal("9.90"),"USD","wechat_pay"));assertEquals(503,error.getStatusCode().value());assertEquals(1,f.requests().size());assertEquals("GET",f.requests().get(0).method());
    }
    @Test void usWechatSupportsUsdAndCnyWithoutConvertingTheQuotedPrice() throws Exception {
        var f=fixture("US");f.gateway().createSession("usd","Credits",new BigDecimal("9.90"),"USD","wechat_pay");f.gateway().createSession("cny","Credits",new BigDecimal("68.80"),"CNY","wechat_pay");
        assertEquals("usd",fields(f.requests().get(1)).get("line_items[0][price_data][currency]"));assertEquals("990",fields(f.requests().get(1)).get("line_items[0][price_data][unit_amount]"));assertEquals("cny",fields(f.requests().get(2)).get("line_items[0][price_data][currency]"));
    }
    @Test void configuredCountryCannotSpoofTheActualMerchantCountryOrMissingCountry() throws Exception {
        for(String actual:List.of("US","")){
            var f=fixture(actual,"GB",false,true,ACCOUNT);assertThrows(ResponseStatusException.class,()->f.gateway().createSession("order","Credits",new BigDecimal("68.80"),"CNY","wechat_pay"));assertEquals(1,f.requests().size());
        }
    }
    @Test void unsupportedCurrenciesMethodsAndFractionalCentsAreRejectedBeforeAnyHttpRequest() throws Exception {
        var f=fixture("GB");
        assertThrows(IllegalArgumentException.class,()->f.gateway().createSession("order","Credits",BigDecimal.TEN,"CNY","card"));
        assertThrows(IllegalArgumentException.class,()->f.gateway().createSession("order","Credits",BigDecimal.TEN,"GBP","wechat_pay"));
        assertThrows(IllegalArgumentException.class,()->f.gateway().createSession("order","Credits",BigDecimal.TEN,"USD","unknown"));
        assertThrows(IllegalArgumentException.class,()->f.gateway().createSession("order","Credits",BigDecimal.ZERO,"CNY","wechat_pay"));
        assertThrows(ArithmeticException.class,()->f.gateway().createSession("order","Credits",new BigDecimal("68.801"),"CNY","wechat_pay"));assertTrue(f.requests().isEmpty());
    }
    @Test void liveChargeSuspensionBlocksNewPaymentsButNotExistingRefundReconciliation() throws Exception {
        var f=fixture("GB","GB",true,false,ACCOUNT);assertEquals("failed",f.gateway().retrieveRefund("re_wallet_fixture").path("status").asString());
        var error=assertThrows(ResponseStatusException.class,()->f.gateway().createSession("order","Credits",new BigDecimal("9.90"),"USD"));assertEquals(503,error.getStatusCode().value());assertEquals(2,f.requests().size());assertTrue(f.requests().stream().allMatch(r->r.method().equals("GET")));
    }
    @Test void wrongMerchantAccountStillBlocksAllReadsAndCreates() throws Exception {
        var f=fixture("GB","GB",true,true,"acct_another_offline123");assertThrows(ResponseStatusException.class,()->f.gateway().retrieveRefund("re_wallet_fixture"));assertThrows(ResponseStatusException.class,()->f.gateway().createSession("order","Credits",new BigDecimal("9.90"),"USD"));assertTrue(f.requests().stream().allMatch(r->r.uri().getPath().equals("/v1/account")));
    }
    @Test void retryRetainsExactMethodCurrencyAmountAndStableIdempotencyIdentity() throws Exception {
        var f=fixture("GB");for(int i=0;i<2;i++)f.gateway().createSession("same-order","Credits",new BigDecimal("178.80"),"CNY","wechat_pay");
        assertEquals(fields(f.requests().get(1)),fields(f.requests().get(2)));assertEquals(f.requests().get(1).headers().firstValue("Idempotency-Key"),f.requests().get(2).headers().firstValue("Idempotency-Key"));assertEquals("17880",fields(f.requests().get(1)).get("line_items[0][price_data][unit_amount]"));
    }
}
