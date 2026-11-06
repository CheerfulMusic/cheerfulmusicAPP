package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Server-owned prices. The client chooses only an identifier, never an amount. */
@Component
@Profile({"mysql", "test"})
public final class PaymentCatalog {
    public record Package(String id, String title, BigDecimal amount, String currency, long credits) {
        public Map<String,Object> asMap() {
            return Map.of("id", id, "title", title, "amount", amount, "currency", currency, "credits", credits);
        }
    }
    private final List<Package> packages;
    public PaymentCatalog(Environment env) {
        packages=List.of(
            item(env,"starter","USD","4.99",100),item(env,"standard","USD","12.99",300),item(env,"plus","USD","24.99",700));
    }
    private static Package item(Environment env,String tier,String currency,String defaultAmount,long credits) {
        String key="qingfeng.payments.prices."+currency.toLowerCase(java.util.Locale.ROOT)+"."+tier;
        BigDecimal amount;
        try { amount=new BigDecimal(env.getProperty(key,defaultAmount)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid payment price: "+key,e); }
        if(amount.signum()<=0 || amount.scale()>2) throw new IllegalArgumentException("Invalid payment price: "+key);
        return new Package(tier+"-"+currency,credits+" Credits",amount,currency,credits);
    }
    public List<Package> packages(){ return packages; }
    public Optional<Package> find(String id){ return packages.stream().filter(p -> p.id().equals(id)).findFirst(); }
}
