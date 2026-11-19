package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-owned Checkout offers. Availability here describes this integration's
 * configuration, not Stripe's approval of a merchant or a customer's wallet.
 * Apple Pay is presented by hosted Checkout through its card payment method.
 */
public final class StripePaymentMethods {
    private static final Set<String> METHODS = Set.of("card", "apple_pay", "wechat_pay");
    private static final String WECHAT_CNY_MINI_ID = "mini-CNY-wechat_pay";
    private static final Pattern WECHAT_OFFER = Pattern.compile("(starter|standard|plus)-(USD|CNY|GBP)-wechat_pay");
    private static final List<String> WECHAT_CURRENCIES = List.of("USD", "CNY", "GBP");

    public record Offer(String id, String title, BigDecimal amount, String currency,
                        long credits, String paymentMethod) {
        public Map<String, Object> asMap() {
            return Map.of("id", id, "title", title, "amount", amount, "currency", currency,
                "credits", credits, "paymentMethod", paymentMethod);
        }
    }

    private final List<PaymentCatalog.Package> usdPackages;
    private final List<Offer> wechatOffers;
    private final String businessCountry;
    private final boolean cardEnabled;
    private final boolean applePayEnabled;
    private final boolean wechatPayEnabled;

    public StripePaymentMethods(PaymentCatalog catalog, Environment environment) {
        usdPackages = catalog.packages().stream().filter(pack -> "USD".equals(pack.currency())).toList();
        businessCountry = environment.getProperty("qingfeng.stripe.business-country", "GB")
            .trim().toUpperCase(Locale.ROOT);
        cardEnabled = environment.getProperty("qingfeng.stripe.payment-methods.card.enabled", Boolean.class, true);
        applePayEnabled = environment.getProperty("qingfeng.stripe.payment-methods.apple-pay.enabled", Boolean.class, true);
        wechatPayEnabled = environment.getProperty("qingfeng.stripe.payment-methods.wechat-pay.enabled", Boolean.class, false);

        var offers = new ArrayList<Offer>();
        for (var pack : usdPackages) offers.add(wechatOffer(pack, pack.amount(), "USD"));
        offers.add(new Offer(WECHAT_CNY_MINI_ID, "35 Credits", new BigDecimal("29.90"), "CNY", 35, "wechat_pay"));
        offers.add(cnyOffer(catalog, environment, "starter", "68.80"));
        offers.add(cnyOffer(catalog, environment, "standard", "178.80"));
        offers.add(cnyOffer(catalog, environment, "plus", "338.80"));
        var gbpKeys = List.of("starter", "standard", "plus");
        long configuredGbp = gbpKeys.stream().filter(tier -> environment.containsProperty("qingfeng.stripe.prices.gbp." + tier)).count();
        if (configuredGbp != 0 && configuredGbp != 3) throw new IllegalArgumentException("Configure all three GBP prices together");
        for (String tier : gbpKeys) offers.add(currencyOffer(catalog, environment, tier, "GBP", switch(tier) { case "starter" -> "7.99"; case "standard" -> "19.99"; default -> "39.99"; }));
        wechatOffers = List.copyOf(offers);
    }

    /** Existing PayPal, Airwallex and older Stripe clients keep their USD catalog. */
    public List<PaymentCatalog.Package> legacyPackages() { return usdPackages; }

    public String businessCountry() { return businessCountry; }

    /**
     * Prices may be shown while purchases are closed, but enabled remains false.
     * Currency support is separate so the client can explain a disabled currency.
     */
    public List<Map<String, Object>> catalog(boolean purchasingOpen) {
        return List.of(method("card", purchasingOpen), method("apple_pay", purchasingOpen),
            method("wechat_pay", purchasingOpen));
    }

    public Offer select(String packageId, String requestedMethod) {
        String method = requestedMethod == null
            ? isWechatOffer(packageId) ? "wechat_pay" : "card"
            : requestedMethod;
        if (!METHODS.contains(method)) throw rejected(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_UNSUPPORTED");
        String reason = disabledReason(method, true);
        if (!reason.isEmpty()) throw rejected(HttpStatus.SERVICE_UNAVAILABLE, reason);
        if ("wechat_pay".equals(method)) {
            var offer = wechatOffers.stream().filter(item -> item.id().equals(packageId)).findFirst()
                .orElseThrow(() -> rejected(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_PACKAGE"));
            if (!supportedCurrencies(method).contains(offer.currency()))
                throw rejected(HttpStatus.BAD_REQUEST, "CURRENCY_UNSUPPORTED");
            return offer;
        }
        var pack = usdPackages.stream().filter(item -> item.id().equals(packageId)).findFirst()
            .orElseThrow(() -> rejected(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_PACKAGE"));
        return new Offer(pack.id(), pack.title(), pack.amount(), pack.currency(), pack.credits(), method);
    }

    /**
     * The persisted offer id permanently fixes WeChat versus card for retries.
     * This deliberately does not read current prices, country, or feature flags.
     * Existing Stripe package ids and Apple Pay selections retain the card route.
     */
    public static String checkoutMethodForStoredOffer(String packageId) {
        if (PaymentAcceptanceOffers.isAcceptance(packageId)) {
            String method=PaymentAcceptanceOffers.storedMethod(packageId);
            if("paypal".equals(method))throw new IllegalArgumentException("PayPal offer cannot route to Stripe");
            return "wechat_pay".equals(method)?"wechat_pay":"card";
        }
        if (isWechatOffer(packageId)) return "wechat_pay";
        if (packageId != null && packageId.endsWith("-wechat_pay"))
            throw new IllegalArgumentException("Invalid persisted WeChat offer");
        return "card";
    }

    private static boolean isWechatOffer(String packageId) {
        return WECHAT_CNY_MINI_ID.equals(packageId) || packageId != null && WECHAT_OFFER.matcher(packageId).matches();
    }

    public Offer selectAcceptance(PaymentCatalog.Package pack,String method){
        if(!METHODS.contains(method)||!PaymentAcceptanceOffers.storedMethod(pack.id()).equals(method))
            throw rejected(HttpStatus.BAD_REQUEST,"PAYMENT_METHOD_UNSUPPORTED");
        String reason=disabledReason(method,true);
        if(!reason.isEmpty())throw rejected(HttpStatus.SERVICE_UNAVAILABLE,reason);
        if(!supportedCurrencies(method).contains(pack.currency()))throw rejected(HttpStatus.BAD_REQUEST,"CURRENCY_UNSUPPORTED");
        return new Offer(pack.id(),pack.title(),pack.amount(),pack.currency(),pack.credits(),method);
    }

    private Map<String, Object> method(String id, boolean purchasingOpen) {
        var currencies = supportedCurrencies(id);
        List<Offer> offers = "wechat_pay".equals(id)
            ? wechatOffers.stream().filter(offer -> currencies.contains(offer.currency())).toList()
            : usdPackages.stream().map(pack -> new Offer(pack.id(), pack.title(), pack.amount(),
                pack.currency(), pack.credits(), id)).toList();
        String reason = disabledReason(id, purchasingOpen);
        List<String> displayedCurrencies = "wechat_pay".equals(id) ? WECHAT_CURRENCIES : List.of("USD");
        var options = displayedCurrencies.stream().map(currency -> {
            boolean supported = currencies.contains(currency);
            String currencyReason = supported ? "" : currencies.isEmpty()
                ? "ACCOUNT_COUNTRY_UNSUPPORTED" : "CURRENCY_UNSUPPORTED";
            return Map.<String, Object>of("currency", currency, "enabled", supported, "reason", currencyReason);
        }).toList();
        return Map.of("id", id, "enabled", reason.isEmpty(), "reason", reason,
            "currencies", currencies, "currencyOptions", options,
            "packages", offers.stream().map(Offer::asMap).toList(),
            "requiresCompatibleDevice", "apple_pay".equals(id));
    }

    private List<String> supportedCurrencies(String method) {
        if (!"wechat_pay".equals(method)) return List.of("USD");
        // These are the two account countries explicitly supported in this release.
        // Never infer that every country in Stripe's overall currency table accepts USD.
        return switch (businessCountry) {
            case "GB" -> wechatOffers.stream().anyMatch(offer -> "GBP".equals(offer.currency())) ? List.of("CNY", "GBP") : List.of("CNY");
            case "US" -> List.of("USD", "CNY");
            default -> List.of();
        };
    }

    private String disabledReason(String method, boolean purchasingOpen) {
        if (!purchasingOpen) return "PAYMENTS_CLOSED";
        boolean configured = switch (method) {
            case "card" -> cardEnabled;
            case "apple_pay" -> cardEnabled && applePayEnabled;
            case "wechat_pay" -> wechatPayEnabled;
            default -> false;
        };
        if (!configured) return "METHOD_DISABLED";
        if (supportedCurrencies(method).isEmpty()) return "ACCOUNT_COUNTRY_UNSUPPORTED";
        return "";
    }

    private static Offer cnyOffer(PaymentCatalog catalog, Environment environment, String tier, String defaultAmount) {
        return currencyOffer(catalog, environment, tier, "CNY", defaultAmount);
    }

    private static Offer currencyOffer(PaymentCatalog catalog, Environment environment, String tier, String currency, String defaultAmount) {
        var original = catalog.find(tier + "-USD")
            .orElseThrow(() -> new IllegalArgumentException("Missing Stripe USD tier: " + tier));
        String key = "qingfeng.stripe.prices." + currency.toLowerCase(Locale.ROOT) + "." + tier;
        BigDecimal amount;
        try {
            amount = new BigDecimal(environment.getProperty(key, defaultAmount));
            if (amount.signum() <= 0 || amount.scale() > 2) throw new ArithmeticException();
            amount.movePointRight(2).longValueExact();
            amount = amount.setScale(2);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("Invalid Stripe price: " + key, error);
        }
        return wechatOffer(original, amount, currency);
    }

    private static Offer wechatOffer(PaymentCatalog.Package original, BigDecimal amount, String currency) {
        String tier = original.id().substring(0, original.id().length() - "-USD".length());
        return new Offer(tier + "-" + currency + "-wechat_pay", original.title(), amount, currency,
            original.credits(), "wechat_pay");
    }

    private static ResponseStatusException rejected(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
