package com.qingfeng.music.server.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded authenticated reporting reads. Never requests payer/shipping fields or follows response URLs. */
final class PayPalReportingGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = List.of("transaction_id", "paypal_reference_id", "paypal_reference_id_type",
        "transaction_event_code", "transaction_initiation_date", "transaction_updated_date", "transaction_amount", "transaction_status");
    static final class Unavailable extends RuntimeException {
        private final String code;
        Unavailable(String code) { super(code); this.code=code; }
        String code() { return code; }
    }
    private final String merchant;
    private final Function<String, JsonNode> get;
    PayPalReportingGateway(String merchant, Function<String, JsonNode> get) {
        if(merchant==null||merchant.isBlank()||get==null)throw new IllegalArgumentException("Authenticated reporting scope required");
        this.merchant=merchant;this.get=get;
    }
    JsonNode read(Instant start, Instant end) { return read(start,end,false); }
    JsonNode refunds(Instant start,Instant end) { return read(start,end,true); }
    private JsonNode read(Instant start, Instant end,boolean refundsOnly) {
        require(start!=null&&end!=null&&start.isBefore(end)
            &&Duration.between(start,end).compareTo(Duration.ofDays(186))<=0,"REPORT_WINDOW_UNSUPPORTED");
        start=start.truncatedTo(ChronoUnit.SECONDS);end=end.truncatedTo(ChronoUnit.SECONDS);
        Instant requestedStart=start, refreshed=null, covered=null;
        Map<String, JsonNode> transactions=new LinkedHashMap<>();
        int pagesRead=0;
        while(start.isBefore(end)) {
            Instant windowEnd=start.plus(Duration.ofDays(30));if(windowEnd.isAfter(end))windowEnd=end;
            int pages=1, total=-1, received=0;Instant windowRefreshed=null, windowCovered=null;
            Set<String> windowTransactionIds=new HashSet<>();
            for(int page=1;page<=pages;page++) {
                require(++pagesRead<=100,"REPORT_PAGE_BUDGET_EXCEEDED");
                String path="/v1/reporting/transactions?start_date="+encode(start.toString())+"&end_date="+encode(windowEnd.toString())
                    +(refundsOnly?"&transaction_type=T1107":"")+"&fields=transaction_info&balance_affecting_records_only=Y&page_size=500&page="+page;
                JsonNode response=read(path);
                require(response!=null&&response.isObject()&&merchant.equals(text(response.path("account_number"))),"REPORT_MERCHANT_MISMATCH");
                require(integer(response.path("page"))==page,"REPORT_PAGE_MISMATCH");
                int reportedPages=integer(response.path("total_pages")), reportedTotal=integer(response.path("total_items"));
                require(reportedTotal>=0&&reportedTotal<=50000&&reportedPages>=0&&reportedPages<=100
                    &&(reportedTotal==0?reportedPages<=1:reportedPages==(reportedTotal+499)/500),"REPORT_TOTALS_INVALID");
                Instant servedStart=time(response.path("start_date")), servedEnd=time(response.path("end_date")), watermark=time(response.path("last_refreshed_datetime"));
                require(servedStart.equals(start)&&!servedEnd.isAfter(windowEnd)&&!servedEnd.isBefore(start),"REPORT_RANGE_INCOMPLETE");
                if(page==1) {pages=Math.max(1,reportedPages);total=reportedTotal;windowRefreshed=watermark;windowCovered=servedEnd;}
                else require(total==reportedTotal&&pages==Math.max(1,reportedPages)&&watermark.equals(windowRefreshed)&&servedEnd.equals(windowCovered),"REPORT_CHANGED_DURING_PAGING");
                JsonNode rows=response.path("transaction_details");require(rows.isArray()&&rows.size()<=500,"REPORT_ROWS_INVALID");
                received=Math.addExact(received,rows.size());
                for(JsonNode row:rows) {
                    JsonNode info=row.path("transaction_info");require(info.isObject(),"REPORT_TRANSACTION_MISSING");
                    String id=text(info.path("transaction_id"));require(id.matches("[A-Za-z0-9]{1,32}"),"REPORT_TRANSACTION_ID_INVALID");
                    Map<String,Object> safe=new LinkedHashMap<>();
                    for(String field:FIELDS)if(info.has(field))safe.put(field,info.path(field));
                    JsonNode normalized=JSON.valueToTree(Map.of("transaction_info",safe));
                    JsonNode prior=transactions.putIfAbsent(id,normalized);
                    require(prior==null||prior.equals(normalized),"REPORT_TRANSACTION_CHANGED");
                    // Adjacent inclusive time windows may repeat an unchanged row;
                    // duplicates within one paged result can instead conceal a gap.
                    require(windowTransactionIds.add(id),"REPORT_DUPLICATE_TRANSACTION");
                }
            }
            require(received==total,"REPORT_ROWS_INCOMPLETE");
            require(windowEnd.equals(end)||windowEnd.equals(windowCovered),"REPORT_HISTORICAL_GAP");
            refreshed=refreshed==null||windowRefreshed.isBefore(refreshed)?windowRefreshed:refreshed;
            covered=windowCovered;start=windowEnd;
        }
        require(refreshed!=null&&covered!=null,"REPORT_EMPTY_RANGE");
        return JSON.valueToTree(Map.of("account_number",merchant,"complete",true,"start_date",requestedStart.toString(),
            "end_date",covered.toString(),"last_refreshed_datetime",refreshed.toString(),"transaction_details",transactions.values()));
    }
    JsonNode disputes(String capture) {
        require(capture!=null&&capture.matches("[A-Z0-9_]{1,32}"),"DISPUTE_CAPTURE_UNSUPPORTED");
        JsonNode response=read("/v1/customer/disputes?disputed_transaction_id="+encode(capture)+"&page_size=50");
        require(response!=null&&response.isObject()&&response.path("items").isArray(),"DISPUTE_LIST_MISSING");
        for(JsonNode link:response.path("links"))require(!"next".equals(text(link.path("rel"))),"DISPUTE_LIST_INCOMPLETE");
        return response;
    }
    private JsonNode read(String path) {
        try{return get.apply(path);}
        catch(PayPalCheckoutGateway.ProviderHttpException failed) {
            throw new Unavailable(failed.status==401||failed.status==403?"REPORTING_PERMISSION_REQUIRED":"REPORTING_PROVIDER_UNAVAILABLE");
        }
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String text(JsonNode value){return value.isString()?value.asString():"";}
    private static Instant time(JsonNode value){try{return Instant.parse(text(value));}catch(RuntimeException invalid){throw new Unavailable("REPORT_TIMESTAMP_INVALID");}}
    private static int integer(JsonNode value){require(value.isIntegralNumber(),"REPORT_COUNT_INVALID");try{return new java.math.BigInteger(value.toString()).intValueExact();}catch(ArithmeticException invalid){throw new Unavailable("REPORT_COUNT_INVALID");}}
    private static void require(boolean value,String code){if(!value)throw new Unavailable(code);}
}
