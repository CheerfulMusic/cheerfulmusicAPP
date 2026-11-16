package com.qingfeng.music.server.payment;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Pure HTTP-contract fixtures, not an actual PayPal or MySQL acceptance. */
class PayPalReportingGatewayTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Instant START=Instant.parse("2026-11-12T00:00:00Z"),END=START.plusSeconds(3600);
    private static Map<String,Object> row(String id,String amount){return Map.of("transaction_info",Map.of("transaction_id",id,"transaction_event_code","T0006","transaction_amount",Map.of("value",amount,"currency_code","USD"),"transaction_status","S","transaction_initiation_date",START.toString(),"transaction_updated_date",START.toString(),"transaction_note","private payer text must not escape"));}
    private static JsonNode page(int page,int total,int pages,List<Map<String,Object>> rows){return JSON.valueToTree(Map.of("account_number","MERCHANT123","start_date",START.toString(),"end_date",END.toString(),"last_refreshed_datetime",END.toString(),"page",page,"total_items",total,"total_pages",pages,"transaction_details",rows));}
    private static void denied(String expected,Runnable action){var failure=assertThrows(PayPalReportingGateway.Unavailable.class,action::run);assertEquals(expected,failure.code());}
    @Test void readsEveryPageAndDoesNotRetainFreeTextOrFollowUrls(){
        var paths=new ArrayList<String>();var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<500;i++)rows.add(row("TX"+i,"1.00"));
        var gateway=new PayPalReportingGateway("MERCHANT123",path->{paths.add(path);return path.endsWith("page=1")?page(1,501,2,rows):page(2,501,2,List.of(row("TX500","1.00")));});
        var report=gateway.read(START,END);assertEquals(501,report.path("transaction_details").size());assertTrue(report.path("complete").asBoolean());
        assertEquals(2,paths.size());assertTrue(paths.get(0).startsWith("/v1/reporting/transactions?"));assertTrue(paths.get(0).contains("fields=transaction_info&balance_affecting_records_only=Y&page_size=500&page=1"));
        assertFalse(report.toString().contains("private payer text"));assertEquals(END.toString(),report.path("last_refreshed_datetime").asString());
    }
    @Test void forbiddenReportingPermissionIsExplicitAndNeverAnEmptySuccessfulReport(){
        var calls=new AtomicInteger();var gateway=new PayPalReportingGateway("MERCHANT123",p->{calls.incrementAndGet();throw new PayPalCheckoutGateway.ProviderHttpException(403);});
        denied("REPORTING_PERMISSION_REQUIRED",()->gateway.read(START,END));assertEquals(1,calls.get());
    }
    @Test void wrongMerchantCannotReuseAnAuthenticatedResponse(){
        var gateway=new PayPalReportingGateway("MERCHANT123",p->JSON.readTree(page(1,0,0,List.of()).toString().replace("MERCHANT123","OTHER")));
        denied("REPORT_MERCHANT_MISMATCH",()->gateway.read(START,END));
    }
    @Test void missingRowsCannotBeMarkedComplete(){
        var gateway=new PayPalReportingGateway("MERCHANT123",p->page(1,2,1,List.of(row("TX1","1.00"))));
        denied("REPORT_ROWS_INCOMPLETE",()->gateway.read(START,END));
    }
    @Test void pageWatermarkChangesAbortTheSnapshot(){
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<500;i++)rows.add(row("TX"+i,"1.00"));
        var gateway=new PayPalReportingGateway("MERCHANT123",p->p.endsWith("page=1")?page(1,501,2,rows):JSON.readTree(page(2,501,2,List.of(row("TX500","1.00"))).toString().replace("\"last_refreshed_datetime\":\""+END,"\"last_refreshed_datetime\":\""+END.plusSeconds(1))));
        denied("REPORT_CHANGED_DURING_PAGING",()->gateway.read(START,END));
    }
    @Test void changedNativeTransactionDoesNotCollapseIntoAnIdempotentDuplicate(){
        var gateway=new PayPalReportingGateway("MERCHANT123",p->page(1,2,1,List.of(row("TX1","1.00"),row("TX1","2.00"))));
        denied("REPORT_TRANSACTION_CHANGED",()->gateway.read(START,END));
    }
    @Test void unboundedOrInvalidWindowsDoNotMakeProviderCalls(){
        var calls=new AtomicInteger();var gateway=new PayPalReportingGateway("MERCHANT123",p->{calls.incrementAndGet();return null;});
        denied("REPORT_WINDOW_UNSUPPORTED",()->gateway.read(END,START));denied("REPORT_WINDOW_UNSUPPORTED",()->gateway.read(START,START.plusSeconds(187L*86400)));assertEquals(0,calls.get());
    }
    @Test void paginatedDisputesCannotMasqueradeAsSingleCaseEvidence(){
        var gateway=new PayPalReportingGateway("MERCHANT123",p->{assertTrue(p.startsWith("/v1/customer/disputes?disputed_transaction_id=CAPTURE123&"));return JSON.readTree("{\"items\":[],\"links\":[{\"rel\":\"next\",\"href\":\"https://untrusted.invalid/next\"}]}");});
        denied("DISPUTE_LIST_INCOMPLETE",()->gateway.disputes("CAPTURE123"));
    }
    @Test void fractionalAndOverflowingCountsAreRejected(){
        for(String invalid:List.of("1.5","999999999999999999999999")){
            var gateway=new PayPalReportingGateway("MERCHANT123",p->JSON.readTree(page(1,0,0,List.of()).toString().replace("\"page\":1","\"page\":"+invalid)));
            denied("REPORT_COUNT_INVALID",()->gateway.read(START,END));
        }
    }
    @Test void duplicateNativeRowWithinOneWindowCannotConcealMissingTransactions(){
        var gateway=new PayPalReportingGateway("MERCHANT123",p->page(1,2,1,List.of(row("TX1","1.00"),row("TX1","1.00"))));
        denied("REPORT_DUPLICATE_TRANSACTION",()->gateway.read(START,END));
    }
    @Test void historicalIntervalUsesCompleteBoundedWindowsAndOnlyDeduplicatesAtWindowBoundaries(){
        var paths=new ArrayList<String>();var finish=START.plusSeconds(31L*86400);
        var gateway=new PayPalReportingGateway("MERCHANT123",p->{
            paths.add(p);var query=new HashMap<String,String>();for(String arg:p.substring(p.indexOf('?')+1).split("&")){var pair=arg.split("=",2);query.put(pair[0],java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));}
            var from=Instant.parse(query.get("start_date"));var until=Instant.parse(query.get("end_date"));assertTrue(java.time.Duration.between(from,until).compareTo(java.time.Duration.ofDays(30))<=0);
            return JSON.valueToTree(Map.of("account_number","MERCHANT123","start_date",from.toString(),"end_date",until.toString(),"last_refreshed_datetime",finish.toString(),"page",1,"total_items",1,"total_pages",1,"transaction_details",List.of(row("BOUNDARY1","1.00"))));
        });
        var report=gateway.read(START,finish);assertEquals(2,paths.size());assertEquals(1,report.path("transaction_details").size());assertEquals(finish.toString(),report.path("end_date").asString());assertTrue(report.path("complete").asBoolean());
    }
}
