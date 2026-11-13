package com.qingfeng.music.server.payment;

import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual GET request construction with injected HttpClient, never an external call. */
class StripeReconciliationTransportTest {
    @Test @SuppressWarnings("unchecked") void checkoutSearchUsesAccountBoundGetAndValidatedFiniteWindowAndCursor() throws Exception {
        var requests=new ArrayList<HttpRequest>();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(c->{
            HttpRequest request=c.getArgument(0);requests.add(request);var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(request.uri().getPath().equals("/v1/account")?"{\"id\":\"acct_reconcilefixture1234\",\"charges_enabled\":false,\"country\":\"GB\"}":"{\"object\":\"list\",\"data\":[],\"has_more\":false}");return response;
        });
        var gateway=new StripeCheckoutGateway(new ObjectMapper(),http);
        ReflectionTestUtils.setField(gateway,"environment","live");ReflectionTestUtils.setField(gateway,"accountId","acct_reconcilefixture1234");ReflectionTestUtils.setField(gateway,"apiKey","sk_live_offline_not_a_real_key");ReflectionTestUtils.setField(gateway,"webhookSecret","whsec_offline_not_a_real_secret");
        gateway.sessionsCreatedBetween(new StripeCheckoutGateway.InstantRange(1000,82000),null);
        gateway.sessionsCreatedBetween(new StripeCheckoutGateway.InstantRange(1000,82000),"cs_previous_fixture");
        assertEquals(3,requests.size());assertTrue(requests.stream().allMatch(r->"GET".equals(r.method())&&r.bodyPublisher().isEmpty()));
        assertEquals("/v1/account",requests.get(0).uri().getPath());assertEquals("/v1/checkout/sessions",requests.get(1).uri().getPath());
        String query=requests.get(2).uri().getQuery();assertTrue(query.contains("limit=100"));assertTrue(query.contains("created[gte]=1000"));assertTrue(query.contains("created[lte]=82000"));assertTrue(query.contains("starting_after=cs_previous_fixture"));
        assertThrows(IllegalArgumentException.class,()->gateway.sessionsCreatedBetween(new StripeCheckoutGateway.InstantRange(5,1),null));assertThrows(IllegalArgumentException.class,()->gateway.sessionsCreatedBetween(new StripeCheckoutGateway.InstantRange(0,86401),null));
        assertThrows(RuntimeException.class,()->gateway.sessionsCreatedBetween(new StripeCheckoutGateway.InstantRange(1000,82000),"../other?token=unsafe"));assertEquals(3,requests.size());
    }
    @Test void scheduledReconciliationInvokesEveryRegisteredProviderAndFailureIsIsolated(){
        var calls=new ArrayList<String>();
        VerifiedPaymentProcessor first=new VerifiedPaymentProcessor(){public String provider(){return "FIRST";}public boolean processNextEvent(){return false;}public void reconcileOrders(){calls.add("FIRST");throw new IllegalStateException("synthetic provider failure");}};
        VerifiedPaymentProcessor second=new VerifiedPaymentProcessor(){public String provider(){return "SECOND";}public boolean processNextEvent(){return false;}public void reconcileOrders(){calls.add("SECOND");}};
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));try{runner.reconcileBatch();assertEquals(List.of("FIRST","SECOND"),calls);}finally{runner.stop();}
    }
    @Test void legacyRunnerConstructorStillReconcilesExistingPaypal(){
        var paypal=mock(PayPalPaymentService.class);var runner=new PaymentInboxRunner(paypal);try{runner.reconcileBatch();verify(paypal).reconcileOrders();}finally{runner.stop();}
    }
}
