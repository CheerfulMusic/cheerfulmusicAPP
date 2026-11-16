package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic supplier and transactional persistence contract; no real payment calls. */
class StripeOrderCancellationTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final JdbcFencedCreditsLedger ledger=mock(JdbcFencedCreditsLedger.class);
    final StripeCheckoutGateway gateway=mock(StripeCheckoutGateway.class);
    final ObjectMapper json=new ObjectMapper();
    final Map<String,Object> row=new HashMap<>();
    final CreatorPrincipal owner=new CreatorPrincipal("owner");
    StripePaymentService service;
    MockedStatic<CreditSchemaReadiness> readiness;
    @BeforeEach void setup() {
        readiness=mockStatic(CreditSchemaReadiness.class);readiness.when(()->CreditSchemaReadiness.ready(db)).thenReturn(true);
        when(gateway.provider()).thenReturn("STRIPE_SANDBOX");when(gateway.configured()).thenReturn(true);
        service=new StripePaymentService(db,tx,ledger,new PaymentCatalog(new org.springframework.mock.env.MockEnvironment()),gateway,json);
        ReflectionTestUtils.setField(service,"enabled",true);
        when(db.queryForObject(anyString(),eq(Integer.class),eq("STRIPE_SANDBOX"))).thenReturn(0);
        row.putAll(Map.of("id","order","creator_id","owner","status","PENDING","provider_link_id","cs_fixture","amount",new BigDecimal("0.50"),"currency","USD","credits",1L));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?","order","owner","STRIPE_SANDBOX")).thenAnswer(i->List.of(new HashMap<>(row)));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?","order","STRIPE_SANDBOX")).thenAnswer(i->List.of(new HashMap<>(row)));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE","order","STRIPE_SANDBOX")).thenAnswer(i->List.of(new HashMap<>(row)));
        when(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE","owner")).thenReturn(List.of(Map.of("id","owner")));
        doAnswer(i->{Consumer<TransactionStatus> action=i.getArgument(0);action.accept(mock(TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        when(db.update(startsWith("UPDATE payment_order SET status='CANCELLED'"),eq("order"),eq("owner"),eq("STRIPE_SANDBOX"),eq("cs_fixture"))).thenAnswer(i->{if(Set.of("CREATING","CREATION_UNKNOWN","PENDING").contains(row.get("status")))row.put("status","CANCELLED");return 1;});
        when(db.update(startsWith("UPDATE payment_order SET status='PAID'"),eq("cs_fixture"),eq("pi_fixture"),eq("order"),eq("STRIPE_SANDBOX"))).thenAnswer(i->{row.put("status","PAID");return 1;});
    }
    @AfterEach void close(){readiness.close();}
    JsonNode session(String status,String paid){return json.readTree("{\"id\":\"cs_fixture\",\"status\":\""+status+"\",\"payment_status\":\""+paid+"\",\"mode\":\"payment\",\"livemode\":false,\"metadata\":{\"order_id\":\"order\"},\"client_reference_id\":\"order\",\"amount_total\":50,\"currency\":\"usd\",\"payment_intent\":\"pi_fixture\"}");}
    void unchanged(){assertEquals("PENDING",row.get("status"));verifyNoInteractions(ledger);verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void closesSupplierBeforeCancellationAndLocksOwnerBeforeOrder(){
        when(gateway.retrieveSession("cs_fixture")).thenReturn(session("open","unpaid"),session("expired","unpaid"));
        assertEquals("CANCELLED",service.cancel(owner,"order").get("status"));
        var order=inOrder(gateway,db);order.verify(gateway).expireSession("cs_fixture");order.verify(gateway).retrieveSession("cs_fixture");
        order.verify(db).queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE","owner");
        order.verify(db).queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE","order","STRIPE_SANDBOX");verifyNoInteractions(ledger);
    }
    @Test void duplicateExpiredCancellationDoesNotTouchCreditsOrReexpire(){
        when(gateway.retrieveSession("cs_fixture")).thenReturn(session("expired","unpaid"));
        assertEquals("CANCELLED",service.cancel(owner,"order").get("status"));assertEquals("CANCELLED",service.cancel(owner,"order").get("status"));
        verify(gateway,never()).expireSession(anyString());verifyNoInteractions(ledger);
    }
    @Test void anotherOwnerCannotQueryOrCancelSupplierSession(){
        assertThrows(ResponseStatusException.class,()->service.cancel(new CreatorPrincipal("other"),"order"));
        verify(gateway,never()).retrieveSession(anyString());verify(gateway,never()).expireSession(anyString());unchanged();
    }
    @Test void noSessionIdentityCannotBeCancelled(){row.remove("provider_link_id");assertThrows(ResponseStatusException.class,()->service.cancel(owner,"order"));verify(gateway,never()).expireSession(anyString());unchanged();}
    @Test void processingPaymentIsNotCancellation(){when(gateway.retrieveSession("cs_fixture")).thenReturn(session("complete","unpaid"));assertThrows(ResponseStatusException.class,()->service.cancel(owner,"order"));verify(gateway,never()).expireSession(anyString());unchanged();}
    @Test void supplierTimeoutLeavesPending(){when(gateway.retrieveSession("cs_fixture")).thenThrow(new StripeCheckoutGateway.StripeRemoteException(503));assertThrows(StripeCheckoutGateway.StripeRemoteException.class,()->service.cancel(owner,"order"));unchanged();}
    @Test void expirationFailureCannotBeMistakenForSuccess(){when(gateway.retrieveSession("cs_fixture")).thenReturn(session("open","unpaid"));when(gateway.expireSession("cs_fixture")).thenThrow(new StripeCheckoutGateway.StripeRemoteException(409));assertThrows(ResponseStatusException.class,()->service.cancel(owner,"order"));unchanged();}
    @Test void failedExpirationWithAuthoritativeExpiredFactIsRecoverable(){when(gateway.retrieveSession("cs_fixture")).thenReturn(session("open","unpaid"),session("expired","unpaid"));when(gateway.expireSession("cs_fixture")).thenThrow(new StripeCheckoutGateway.StripeRemoteException(503));assertEquals("CANCELLED",service.cancel(owner,"order").get("status"));verifyNoInteractions(ledger);}
    @Test void alreadyPaidDoesNotContactSupplier(){row.put("status","PAID");assertEquals("PAID",service.cancel(owner,"order").get("status"));verify(gateway,never()).retrieveSession(anyString());verifyNoInteractions(ledger);}
    @Test void paidWebhookWinningLocalRaceCannotBeOverwritten(){
        when(gateway.retrieveSession("cs_fixture")).thenAnswer(i->{row.put("status","PAID");return session("expired","unpaid");});
        assertEquals("PAID",service.cancel(owner,"order").get("status"));verifyNoInteractions(ledger);
    }
    @Test void paymentWinningExpirationRaceSettlesInsteadOfCancelling(){
        when(gateway.retrieveSession("cs_fixture")).thenReturn(session("open","unpaid"),session("complete","paid"));when(gateway.expireSession("cs_fixture")).thenThrow(new StripeCheckoutGateway.StripeRemoteException(409));
        assertEquals("PAID",service.cancel(owner,"order").get("status"));verify(ledger).credit("owner",1L,"payment:order");
        assertEquals("PAID",service.cancel(owner,"order").get("status"));verify(ledger,times(1)).credit("owner",1L,"payment:order");
    }
    @Test void wrongSessionAmountCurrencyEnvironmentOrOrderCannotExpire(){
        String original=session("open","unpaid").toString();
        for(String invalid:List.of(original.replace("cs_fixture","cs_other"),original.replace("50","51"),original.replace("usd","cny"),original.replace("false","true"),original.replace("order_id\":\"order","order_id\":\"other"))){
            when(gateway.retrieveSession("cs_fixture")).thenReturn(json.readTree(invalid));assertThrows(ResponseStatusException.class,()->service.cancel(owner,"order"));
        }
        verify(gateway,never()).expireSession(anyString());unchanged();
    }
}
