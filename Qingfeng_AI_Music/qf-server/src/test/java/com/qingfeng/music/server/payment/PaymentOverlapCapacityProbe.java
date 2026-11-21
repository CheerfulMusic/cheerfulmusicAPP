package com.qingfeng.music.server.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.util.ReflectionTestUtils;
import static com.qingfeng.music.server.payment.IntegratedPaymentApplicationMySqlTest.*;
import static org.junit.jupiter.api.Assertions.*;

final class PaymentOverlapCapacityProbe {
    private static final int INPUT_SECONDS = 180;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger callbacks = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AtomicInteger peakDue = new AtomicInteger();
    private final long start = System.nanoTime();

    static void run() throws Exception {
        assertEquals(0, number("SELECT COUNT(*) FROM payment_webhook_inbox"),
                "Select only the overlap probe on a fresh isolated database");
        var config = new Properties();
        try (var input = PaymentOverlapCapacityProbe.class.getResourceAsStream("/application-capacity-5000.properties")) {
            config.load(input);
        }
        Object oldDelay = ReflectionTestUtils.getField(stripe, "initialReconcileDelaySeconds");
        Object oldReuse = ReflectionTestUtils.getField(stripe, "reuseSchemaCheck");
        Object oldComplete = ReflectionTestUtils.getField(stripe, "completeRecoveryOnPaidCallback");
        try {
            ReflectionTestUtils.setField(stripe, "completeRecoveryOnPaidCallback",
                    Boolean.parseBoolean(System.getProperty("qf.payment.capacity.complete-recovery-on-paid-callback",
                            config.getProperty("qingfeng.stripe.complete-recovery-on-paid-callback","false"))));
            ReflectionTestUtils.setField(stripe, "initialReconcileDelaySeconds",
                    Integer.parseInt(config.getProperty("qingfeng.stripe.initial-reconcile-delay-seconds")));
            ReflectionTestUtils.setField(stripe, "reuseSchemaCheck",
                    Boolean.parseBoolean(config.getProperty("qingfeng.stripe.reuse-schema-check")));
            new PaymentOverlapCapacityProbe().measure(config);
        } finally {
            ReflectionTestUtils.setField(stripe, "initialReconcileDelaySeconds", oldDelay);
            ReflectionTestUtils.setField(stripe, "reuseSchemaCheck", oldReuse);
            ReflectionTestUtils.setField(stripe, "completeRecoveryOnPaidCallback", oldComplete);
        }
    }

    private void measure(Properties config) throws Exception {
        int rate = Integer.getInteger("qf.payment.capacity.orders-per-second", 75);
        assertTrue(rate > 0);
        int count = Math.multiplyExact(rate, INPUT_SECONDS);
        int lanes = Integer.getInteger("qf.payment.capacity.worker-concurrency",
                Integer.parseInt(config.getProperty("qingfeng.payment.inbox.worker-concurrency", "4")));
        var workers = new PaymentInboxRunner(paypal, List.of(stripe, paypal), lanes,
                Integer.parseInt(config.getProperty("qingfeng.payment.inbox.batch-size")));
        int scanMs = Integer.parseInt(config.getProperty("qingfeng.payment.inbox.scan-ms"));
        var scheduler = Executors.newScheduledThreadPool(3);
        var producers = Executors.newFixedThreadPool(24);
        var results = new ArrayList<Future<Paid>>(count);
        try {
            scheduler.scheduleWithFixedDelay(workers::runOnce, 0, scanMs, TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(workers::reconcile, 60, 60, TimeUnit.SECONDS);
            var sampler = scheduler.scheduleWithFixedDelay(this::sample, 5, 5, TimeUnit.SECONDS);
            for (int index = 0; index < count; index++) {
                long gap = start + index * 1_000_000_000L / rate - System.nanoTime();
                if (gap > 0) TimeUnit.NANOSECONDS.sleep(gap);
                int sequence = index;
                results.add(producers.submit(() -> createAndNotify(sequence)));
            }
            var orders = new ArrayList<Paid>(count);
            for (var result : results) orders.add(result.get(90, TimeUnit.SECONDS));
            System.out.printf("FINISHED_INPUT seconds=%.2f n=%d%n", elapsed(), count);
            int expectedCallbacks = count - (count + 99) / 100;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(6);
            while (recoveries.get() != count || callbacks.get() != expectedCallbacks) {
                if (sampler.isDone()) sampler.get();
                assertTrue(System.nanoTime() < deadline, "Payment queue did not drain");
                Thread.sleep(500);
            }
            for (var order : orders) verifySettlement(order);
            long confirmedByCallback = number("""
                    SELECT COUNT(*) FROM payment_inbox_attempt
                    WHERE result='DONE' AND attempt=0 AND evidence_id LIKE 'stripe-paid-callback:%'
                    """);
            System.out.printf("RECOVERY confirmedByCallback=%d processedByWorker=%d%n",
                    confirmedByCallback, count - confirmedByCallback);
            reportLatency(orders, expectedCallbacks,
                    Integer.parseInt(config.getProperty("qingfeng.stripe.initial-reconcile-delay-seconds")));
            System.out.printf("VERIFIED orders=%d callbacks=%d recoveries=%d peakDue=%d%n",
                    count, callbacks.get(), recoveries.get(), peakDue.get());
        } finally {
            producers.shutdownNow();
            scheduler.shutdownNow();
            workers.stop();
        }
    }

    private static void reportLatency(List<Paid> orders, int callbacks, int firstRecoveryDelay) {
        if (callbacks > 0) {
            long p95 = number("""
                    SELECT TIMESTAMPDIFF(SECOND, created_at, updated_at) AS elapsed_seconds
                    FROM payment_webhook_inbox WHERE event_type='checkout.session.completed' AND status='DONE'
                    ORDER BY elapsed_seconds LIMIT 1 OFFSET ?
                    """, percentile95Index(callbacks));
            System.out.printf("CALLBACK count=%d recordedToDoneP95Seconds=%d%n", callbacks, p95);
        }
        var lateRecoverySeconds = new ArrayList<Long>();
        for (int index = 0; index < orders.size(); index += 100) {
            long elapsed = number("""
                    SELECT TIMESTAMPDIFF(SECOND, created_at, updated_at)
                    FROM payment_webhook_inbox WHERE event_id=? AND status='DONE'
                    """, "reconcile-order-" + orders.get(index).order());
            lateRecoverySeconds.add(elapsed - firstRecoveryDelay);
        }
        lateRecoverySeconds.sort(Long::compare);
        System.out.printf("MISSING_CALLBACK count=%d dueToDoneP95Seconds=%d maxSeconds=%d%n",
                lateRecoverySeconds.size(), lateRecoverySeconds.get(percentile95Index(lateRecoverySeconds.size())),
                lateRecoverySeconds.get(lateRecoverySeconds.size() - 1));
    }

    private static int percentile95Index(int count) {
        return (int) Math.ceil(0.95 * count) - 1;
    }

    private Paid createAndNotify(int sequence) throws Exception {
        Paid order = pending(owner());
        SESSIONS.put(order.session(), session(order.session(), order.order(), true));
        created.incrementAndGet();
        if (sequence % 100 != 0) {
            String event = "evt_longstream" + sequence;
            accepted("checkout.session.completed", order.session(), event);
            if (sequence % 100 == 1) accepted("checkout.session.completed", order.session(), event);
        }
        return order;
    }

    private void sample() {
        var rows = db.queryForList("""
                SELECT event_type, SUM(status='DONE') AS done,
                       SUM(status<>'DONE' AND next_attempt_at<=CURRENT_TIMESTAMP) AS due
                FROM payment_webhook_inbox GROUP BY event_type
                """);
        int due = 0;
        for (var row : rows) {
            int done = ((Number) row.get("done")).intValue();
            due += ((Number) row.get("due")).intValue();
            switch ((String) row.get("event_type")) {
                case "stripe.order.reconcile" -> recoveries.set(done);
                case "checkout.session.completed" -> callbacks.set(done);
                default -> throw new IllegalStateException("Unexpected event in isolated capacity test");
            }
        }
        peakDue.accumulateAndGet(due, Math::max);
        System.out.printf("SAMPLE t=%.1f receivedOrders=%d callbacksDone=%d recoveryDone=%d eligiblePending=%d%n",
                elapsed(), created.get(), callbacks.get(), recoveries.get(), due);
    }

    private double elapsed() { return (System.nanoTime() - start) / 1e9; }

    private static void verifySettlement(Paid order) {
        assertEquals("PAID", db.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, order.order()));
        assertEquals(1, number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND reference_id=? AND entry_type='CREDIT' AND amount=100",
                order.owner().creatorId(), "payment:" + order.order()));
        assertEquals(100, ledger.balance(order.owner().creatorId()));
    }
}
