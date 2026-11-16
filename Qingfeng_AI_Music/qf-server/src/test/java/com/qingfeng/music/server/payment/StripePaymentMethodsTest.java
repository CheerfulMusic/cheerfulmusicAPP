package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Pure configuration and quote contracts; no database or Stripe requests. */
class StripePaymentMethodsTest {
    private static MockEnvironment wechat(String country) {
        return new MockEnvironment().withProperty("qingfeng.stripe.business-country", country)
            .withProperty("qingfeng.stripe.payment-methods.wechat-pay.enabled", "true");
    }

    private static StripePaymentMethods methods(MockEnvironment environment) {
        return new StripePaymentMethods(new PaymentCatalog(environment), environment);
    }

    private static Map<String, Object> entry(StripePaymentMethods methods, boolean open, String id) {
        return methods.catalog(open).stream().filter(method -> id.equals(method.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packages(Map<String, Object> method) {
        return (List<Map<String, Object>>) method.get("packages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> currency(Map<String, Object> method, String code) {
        return ((List<Map<String, Object>>) method.get("currencyOptions")).stream()
            .filter(option -> code.equals(option.get("currency"))).findFirst().orElseThrow();
    }

    private static void rejected(HttpStatus status, String reason, Runnable request) {
        var failure = assertThrows(ResponseStatusException.class, request::run);
        assertEquals(status, failure.getStatusCode());
        assertEquals(reason, failure.getReason());
    }

    @Test void gbpPricesRequireCompleteConfigurationAndStaySeparateFromExistingQuotes() {
        var defaults=methods(wechat("GB"));
        assertEquals(new BigDecimal("7.99"),defaults.select("starter-GBP-wechat_pay","wechat_pay").amount());
        assertEquals(new BigDecimal("19.99"),defaults.select("standard-GBP-wechat_pay","wechat_pay").amount());
        assertEquals(new BigDecimal("39.99"),defaults.select("plus-GBP-wechat_pay","wechat_pay").amount());
        var env=wechat("GB").withProperty("qingfeng.stripe.prices.gbp.starter","7.80");
        assertThrows(IllegalArgumentException.class,()->methods(env));
        env.withProperty("qingfeng.stripe.prices.gbp.standard","20.80").withProperty("qingfeng.stripe.prices.gbp.plus","39.80");
        var m=methods(env);assertEquals(List.of("CNY","GBP"),entry(m,true,"wechat_pay").get("currencies"));
        assertEquals(new BigDecimal("7.80"),m.select("starter-GBP-wechat_pay","wechat_pay").amount());
        assertEquals("wechat_pay",StripePaymentMethods.checkoutMethodForStoredOffer("starter-GBP-wechat_pay"));
        assertEquals(new BigDecimal("68.80"),m.select("starter-CNY-wechat_pay","wechat_pay").amount());
        for(String invalid:List.of("0","-1","1.001","NaN")) {
            env.withProperty("qingfeng.stripe.prices.gbp.starter",invalid);
            assertThrows(IllegalArgumentException.class,()->methods(env));
        }
    }
    @Test void approvedCnyQuotesAreIndependentOfTheUnchangedUsdPackages() {
        var methods = methods(wechat("GB"));
        assertEquals(List.of(new BigDecimal("9.90"), new BigDecimal("25.90"), new BigDecimal("49.90")),
            methods.legacyPackages().stream().map(PaymentCatalog.Package::amount).toList());
        assertEquals(List.of(new BigDecimal("68.80"), new BigDecimal("178.80"), new BigDecimal("338.80")),
            packages(entry(methods, true, "wechat_pay")).stream().filter(pack -> "CNY".equals(pack.get("currency"))).map(pack -> (BigDecimal) pack.get("amount")).toList());
        assertEquals(List.of(100L, 300L, 700L),
            packages(entry(methods, true, "wechat_pay")).stream().filter(pack -> "CNY".equals(pack.get("currency"))).map(pack -> (Long) pack.get("credits")).toList());
        assertEquals("starter-CNY-wechat_pay", methods.select("starter-CNY-wechat_pay", "wechat_pay").id());
    }

    @Test void gbWechatOnlyOffersCnyAndExplainsUnavailableUsd() {
        var methods = methods(wechat("GB"));
        var method = entry(methods, true, "wechat_pay");
        assertEquals(true, method.get("enabled"));
        assertEquals(List.of("CNY", "GBP"), method.get("currencies"));
        assertEquals(true, currency(method, "CNY").get("enabled"));
        assertEquals(false, currency(method, "USD").get("enabled"));
        assertEquals("CURRENCY_UNSUPPORTED", currency(method, "USD").get("reason"));
        assertTrue(packages(method).stream().allMatch(pack -> List.of("CNY", "GBP").contains(pack.get("currency"))));
        rejected(HttpStatus.BAD_REQUEST, "CURRENCY_UNSUPPORTED",
            () -> methods.select("starter-USD-wechat_pay", "wechat_pay"));
    }

    @Test void usWechatCanQuoteUsdAndCnyWithoutChangingOriginalUsdPrices() {
        var environment = wechat("US").withProperty("qingfeng.payments.prices.usd.starter", "10.25");
        var methods = methods(environment);
        var method = entry(methods, true, "wechat_pay");
        assertEquals(List.of("USD", "CNY"), method.get("currencies"));
        assertEquals(6, packages(method).size());
        assertEquals(new BigDecimal("10.25"), methods.select("starter-USD", "card").amount());
        assertEquals(new BigDecimal("10.25"), methods.select("starter-USD-wechat_pay", "wechat_pay").amount());
        assertEquals(new BigDecimal("68.80"), methods.select("starter-CNY-wechat_pay", "wechat_pay").amount());
    }

    @Test void unsupportedCountryFailsClosedOnlyForWechat() {
        var methods = methods(wechat("HK"));
        var method = entry(methods, true, "wechat_pay");
        assertEquals(false, method.get("enabled"));
        assertEquals("ACCOUNT_COUNTRY_UNSUPPORTED", method.get("reason"));
        assertEquals(List.of(), method.get("currencies"));
        assertEquals(List.of(), packages(method));
        assertEquals("ACCOUNT_COUNTRY_UNSUPPORTED", currency(method, "CNY").get("reason"));
        rejected(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_COUNTRY_UNSUPPORTED",
            () -> methods.select("starter-CNY-wechat_pay", "wechat_pay"));
        assertEquals("card", methods.select("starter-USD", "card").paymentMethod());
        assertEquals("apple_pay", methods.select("starter-USD", "apple_pay").paymentMethod());
    }

    @Test void closingNewPurchasesDisablesEveryMethodButRetainsHonestPreviewPrices() {
        var methods = methods(wechat("GB"));
        for (var method : methods.catalog(false)) {
            assertEquals(false, method.get("enabled"));
            assertEquals("PAYMENTS_CLOSED", method.get("reason"));
            assertEquals("wechat_pay".equals(method.get("id")) ? 6 : 3, packages(method).size());
        }
        // Country support is not confused with the global purchase gate.
        assertEquals(true, currency(entry(methods, false, "wechat_pay"), "CNY").get("enabled"));
        assertEquals(false, currency(entry(methods, false, "wechat_pay"), "USD").get("enabled"));
    }

    @Test void defaultWechatIsNotSilentlyActivatedByAddingPrices() {
        var methods = methods(new MockEnvironment());
        var method = entry(methods, true, "wechat_pay");
        assertEquals(false, method.get("enabled"));
        assertEquals("METHOD_DISABLED", method.get("reason"));
        assertEquals(new BigDecimal("68.80"), packages(method).get(0).get("amount"));
        rejected(HttpStatus.SERVICE_UNAVAILABLE, "METHOD_DISABLED",
            () -> methods.select("starter-CNY-wechat_pay", "wechat_pay"));
    }

    @Test void disablingCardsAlsoDisablesApplePayButNotAnEnabledWechatMethod() {
        var methods = methods(wechat("GB").withProperty("qingfeng.stripe.payment-methods.card.enabled", "false"));
        for (String id : List.of("card", "apple_pay")) {
            assertEquals(false, entry(methods, true, id).get("enabled"));
            rejected(HttpStatus.SERVICE_UNAVAILABLE, "METHOD_DISABLED", () -> methods.select("starter-USD", id));
        }
        assertEquals(true, entry(methods, true, "wechat_pay").get("enabled"));
        assertEquals("wechat_pay", methods.select("starter-CNY-wechat_pay", "wechat_pay").paymentMethod());
    }

    @Test void disablingApplePayDoesNotDisableOrdinaryCardPayments() {
        var methods = methods(new MockEnvironment()
            .withProperty("qingfeng.stripe.payment-methods.apple-pay.enabled", "false"));
        assertEquals(true, entry(methods, true, "card").get("enabled"));
        assertEquals(false, entry(methods, true, "apple_pay").get("enabled"));
        rejected(HttpStatus.SERVICE_UNAVAILABLE, "METHOD_DISABLED", () -> methods.select("starter-USD", "apple_pay"));
        assertEquals("card", methods.select("starter-USD", "card").paymentMethod());
    }

    @Test void applePayDescribesADeviceDependentCardFamilySelectionNotAnotherProvider() {
        var methods = methods(new MockEnvironment());
        var apple = entry(methods, true, "apple_pay");
        assertEquals(true, apple.get("requiresCompatibleDevice"));
        assertEquals(false, entry(methods, true, "card").get("requiresCompatibleDevice"));
        var selected = methods.select("starter-USD", "apple_pay");
        assertEquals("starter-USD", selected.id());
        assertEquals("apple_pay", selected.paymentMethod());
        assertEquals("card", StripePaymentMethods.checkoutMethodForStoredOffer(selected.id()));
        assertEquals("apple_pay", packages(apple).get(0).get("paymentMethod"));
    }

    @Test void oldApiRequestsRemainCardsAndWechatOfferIdsAreUnambiguous() {
        var methods = methods(wechat("US"));
        assertEquals("card", methods.select("starter-USD", null).paymentMethod());
        assertEquals("wechat_pay", methods.select("starter-CNY-wechat_pay", null).paymentMethod());
        assertEquals("wechat_pay", methods.select("starter-USD-wechat_pay", null).paymentMethod());
        rejected(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_PACKAGE",
            () -> methods.select("starter-CNY-wechat_pay", "card"));
        rejected(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_PACKAGE",
            () -> methods.select("starter-USD", "wechat_pay"));
    }

    @Test void forgedMethodsAndQuotesCannotSelectAnotherRouteOrArbitraryPrice() {
        var methods = methods(wechat("US"));
        for (String id : List.of("", "CARD", " credit_card", "paypal", "bank_transfer"))
            rejected(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_UNSUPPORTED", () -> methods.select("starter-USD", id));
        for (String offer : List.of("starter-CNY", "starter-CNY-wechat_pay?amount=1", "starter-EUR-wechat_pay",
                "unknown-CNY-wechat_pay", "starter-cny-wechat_pay"))
            rejected(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_PACKAGE", () -> methods.select(offer, "wechat_pay"));
    }

    @Test void permanentCheckoutRouteDoesNotReadNewCountryPricesOrFlags() {
        var changed = methods(new MockEnvironment()
            .withProperty("qingfeng.stripe.business-country", "DE")
            .withProperty("qingfeng.stripe.prices.cny.starter", "99.00"));
        assertEquals(false, entry(changed, true, "wechat_pay").get("enabled"));
        for (String tier : List.of("starter", "standard", "plus")) {
            assertEquals("wechat_pay", StripePaymentMethods.checkoutMethodForStoredOffer(tier + "-CNY-wechat_pay"));
            assertEquals("wechat_pay", StripePaymentMethods.checkoutMethodForStoredOffer(tier + "-USD-wechat_pay"));
            assertEquals("card", StripePaymentMethods.checkoutMethodForStoredOffer(tier + "-USD"));
        }
        assertEquals("card", StripePaymentMethods.checkoutMethodForStoredOffer(null));
        assertEquals("card", StripePaymentMethods.checkoutMethodForStoredOffer("legacy-package"));
        assertThrows(IllegalArgumentException.class,
            () -> StripePaymentMethods.checkoutMethodForStoredOffer("starter-EUR-wechat_pay"));
    }

    @Test void invalidCnyPricesAreRejectedInsteadOfRoundedOrConverted() {
        for (String amount : List.of("", "bad", "0", "-1", "68.801", "68.800", "100000000000000000000000")) {
            var environment = wechat("GB").withProperty("qingfeng.stripe.prices.cny.starter", amount);
            assertThrows(IllegalArgumentException.class, () -> methods(environment), amount);
        }
        var methods = methods(wechat("GB").withProperty("qingfeng.stripe.prices.cny.starter", "69.5"));
        assertEquals(new BigDecimal("69.50"), methods.select("starter-CNY-wechat_pay", "wechat_pay").amount());
    }

    @Test void stripeCnyCatalogNeverLeaksIntoOtherProvidersPaymentCatalog() {
        var environment = wechat("US");
        var original = new PaymentCatalog(environment);
        var methods = new StripePaymentMethods(original, environment);
        assertEquals(3, original.packages().size());
        assertTrue(original.packages().stream().allMatch(pack -> "USD".equals(pack.currency())));
        assertTrue(original.find("starter-CNY-wechat_pay").isEmpty());
        assertTrue(original.find("starter-CNY").isEmpty());
        assertEquals(original.packages(), methods.legacyPackages());
        assertThrows(UnsupportedOperationException.class,
            () -> methods.legacyPackages().add(new PaymentCatalog.Package("x", "x", BigDecimal.ONE, "CNY", 1)));
    }

    @Test void configuredCountryIsNormalizedWithoutClaimingUnverifiedStripeApproval() {
        var methods = methods(wechat(" gb "));
        assertEquals("GB", methods.businessCountry());
        assertEquals(List.of("CNY", "GBP"), entry(methods, true, "wechat_pay").get("currencies"));
        assertEquals("", entry(methods, true, "wechat_pay").get("reason"));
        assertThrows(UnsupportedOperationException.class,
            () -> entry(methods, true, "wechat_pay").put("enabled", false));
    }
}
