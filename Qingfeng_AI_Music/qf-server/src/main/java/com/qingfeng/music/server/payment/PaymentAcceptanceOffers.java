package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Expiring, one-owner purchase quotes. Persisted orders always use their original snapshot. */
public final class PaymentAcceptanceOffers {
    private static final String PREFIX="qingfeng.payment.acceptance.";
    private static final Pattern OWNER=Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern ID=Pattern.compile("accept-([0-9a-f]{8})-(card|apple_pay|wechat_pay|paypal)-(USD|CNY|GBP)");
    private final boolean enabled;
    private final String owner,nonce;
    private final Instant expires;
    private final BigDecimal wechat;
    private final String wechatCurrency;
    private final Clock clock;

    public PaymentAcceptanceOffers(Environment env){this(env,Clock.systemUTC());}
    PaymentAcceptanceOffers(Environment env,Clock clock){
        this.clock=clock;enabled=env.getProperty(PREFIX+"enabled",Boolean.class,false);
        owner=env.getProperty(PREFIX+"owner-id","");nonce=env.getProperty(PREFIX+"nonce","");
        if(!enabled){expires=Instant.EPOCH;wechat=new BigDecimal("3.00");wechatCurrency="CNY";return;}
        String expiry=env.getProperty(PREFIX+"expires-at","");
        try{
            if(!OWNER.matcher(owner).matches()||!nonce.matches("[0-9a-f]{8}")||!expiry.endsWith("Z"))throw new IllegalArgumentException();
            expires=Instant.parse(expiry);
            wechatCurrency=env.getProperty(PREFIX+"wechat-currency","CNY").trim().toUpperCase(Locale.ROOT);
            if(!Set.of("CNY","GBP").contains(wechatCurrency))throw new IllegalArgumentException();
            wechat="GBP".equals(wechatCurrency)?new BigDecimal("0.50")
                :new BigDecimal(env.getProperty(PREFIX+"wechat-cny","3.00")).setScale(2,java.math.RoundingMode.UNNECESSARY);
            if("CNY".equals(wechatCurrency)&&wechat.compareTo(new BigDecimal("3.00"))<0)throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid private payment acceptance configuration");}
    }
    public boolean configuredFor(String candidate){return enabled&&owner.equals(candidate);}
    boolean active(String candidate){return configuredFor(candidate)&&Instant.now(clock).isBefore(expires);}
    /** Public purchasing resumes its ordinary catalog after expiry; a closed canary never gains ordinary buying rights. */
    boolean exclusiveFor(String candidate,boolean publicPurchases){return configuredFor(candidate)&&(!publicPurchases||active(candidate));}
    public static boolean isAcceptance(String id){return id!=null&&id.startsWith("accept-");}
    public static String storedMethod(String id){
        var match=ID.matcher(id==null?"":id);
        if(!match.matches())throw new IllegalArgumentException("Invalid persisted acceptance offer");
        boolean wechat="wechat_pay".equals(match.group(2));
        if(wechat?!Set.of("CNY","GBP").contains(match.group(3)):!"USD".equals(match.group(3)))
            throw new IllegalArgumentException("Invalid persisted acceptance offer");
        return match.group(2);
    }
    public Optional<PaymentCatalog.Package> offer(String candidate,String method){
        if(!active(candidate))return Optional.empty();
        if(!"card".equals(method)&&!"apple_pay".equals(method)&&!"paypal".equals(method)&&!"wechat_pay".equals(method))return Optional.empty();
        String currency="wechat_pay".equals(method)?wechatCurrency:"USD";
        BigDecimal amount=switch(method){case "card","apple_pay"->new BigDecimal("0.50");case "paypal"->new BigDecimal("0.01");case "wechat_pay"->wechat;default->throw new IllegalArgumentException("Unsupported acceptance channel");};
        return Optional.of(new PaymentCatalog.Package("accept-"+nonce+"-"+method+"-"+currency,"Temporary payment acceptance",amount,currency,1));
    }
    public PaymentCatalog.Package select(String candidate,String id,String method){
        return offer(candidate,method).filter(p->p.id().equals(id)).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"PAYMENT_ACCEPTANCE_UNAVAILABLE"));
    }
    public boolean unused(JdbcTemplate db,String candidate,String id,String provider){
        Integer count=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND package_id=? AND provider=?",Integer.class,candidate,id,provider);
        return count!=null&&count==0;
    }
    /** Caller must already hold this creator's row lock, before inserting any payment order. */
    public void requireReservation(JdbcTemplate db,String candidate,String id,String provider){
        if(!isAcceptance(id))return;
        select(candidate,id,storedMethod(id));
        if(!unused(db,candidate,id,provider))throw new ResponseStatusException(HttpStatus.CONFLICT,"PAYMENT_ACCEPTANCE_ALREADY_RESERVED");
    }
    public Map<String,Object> view(PaymentCatalog.Package pack,String method){
        var result=new LinkedHashMap<String,Object>(pack.asMap());
        result.put("acceptance",true);result.put("expiresAt",expires.toString());result.put("maxPurchases",1);
        if(!"paypal".equals(method))result.put("paymentMethod",method);
        return Map.copyOf(result);
    }
}
