package bank.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultInjectorTest {

    private static final FaultAction DROP = FaultAction.drop();
    private static final FaultAction DELAY = FaultAction.delay(Duration.ofMillis(250));
    private static final FaultPoint BEFORE = FaultPoint.SERVER_BEFORE_EXECUTION;
    private static final FaultPoint AFTER = FaultPoint.SERVER_AFTER_COMMIT;
    private static final FaultPoint CLIENT = FaultPoint.CLIENT_BEFORE_SEND;

    private static String show(Optional<FaultAction> decision) {
        return decision.map(a -> a.isDrop() ? "DROP" : "DELAY" + a.delayMillis()).orElse("-");
    }

    private static FaultConfig mixedConfig(long seed) {
        return FaultConfig.of(seed,
                FaultRule.withProbability(AFTER, DROP, 0.25),
                FaultRule.withProbability(AFTER, DELAY, 0.25),
                FaultRule.withProbability(BEFORE, DROP, 0.5),
                FaultRule.withProbability(CLIENT, DROP, 0.1));
    }

    /** Asks at every fault point, several rounds, for one request identity. */
    private static List<String> consult(FaultInjector injector, String account, long key,
                                        int rounds) {
        List<String> seen = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (FaultPoint point : FaultPoint.values()) {
                seen.add(show(injector.decide(point, account, key)));
            }
        }
        return seen;
    }

    private static List<String> sweep(FaultInjector injector) {
        List<String> seen = new ArrayList<>();
        for (int account = 0; account < 5; account++) {
            for (long key = 1; key <= 5; key++) {
                seen.addAll(consult(injector, "ACC-" + account, key, 20));
            }
        }
        return seen;
    }

    private static List<String> rateSample(long seed) {
        FaultInjector injector =
                FaultInjector.of(FaultConfig.of(seed, FaultRule.withProbability(AFTER, DROP, 0.5)));
        List<String> seen = new ArrayList<>();
        for (long key = 1; key <= 1000; key++) {
            seen.add(show(injector.decide(AFTER, "ACC-1", key)));
        }
        return seen;
    }

    @Test
    @DisplayName("FR-024: a disabled injector never injects a fault and never creates counters")
    void disabledInjectorDoesNothing() {
        FaultInjector injector = FaultInjector.disabled();
        assertFalse(injector.isEnabled());
        for (FaultPoint point : FaultPoint.values()) {
            for (int account = 0; account < 20; account++) {
                for (long key = 1; key <= 20; key++) {
                    assertEquals(Optional.empty(), injector.decide(point, "ACC-" + account, key));
                }
            }
        }
        assertEquals(0, injector.trackedRequests());
        assertEquals(0, injector.occurrences(AFTER, "ACC-1", 1));
    }

    @Test
    @DisplayName("FR-020: an enabled injector counts consultations per request identity")
    void enabledInjectorCountsPerRequestIdentity() {
        FaultInjector injector = FaultInjector.of(FaultConfig.of(1));
        assertTrue(injector.isEnabled());
        for (int i = 0; i < 3; i++) {
            assertEquals(Optional.empty(), injector.decide(AFTER, "ACC-1", 1));
        }
        assertEquals(3, injector.occurrences(AFTER, "ACC-1", 1));
        assertEquals(0, injector.occurrences(AFTER, "ACC-1", 2));
        assertEquals(0, injector.occurrences(BEFORE, "ACC-1", 1));
        assertEquals(0, injector.occurrences(AFTER, "ACC-2", 1));
        assertEquals(1, injector.trackedRequests());
    }

    @Test
    @DisplayName("FR-020: 'first N occurrences' fires exactly N times")
    void firstTimesFiresExactlyThatOften() {
        FaultInjector injector =
                FaultInjector.of(FaultConfig.of(1, FaultRule.firstTimes(AFTER, DROP, 2)));
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seen.add(show(injector.decide(AFTER, "ACC-1", 1)));
        }
        assertEquals(List.of("DROP", "DROP", "-", "-", "-"), seen);
    }

    @Test
    @DisplayName("FR-020: occurrences are counted separately for each account and key")
    void occurrencesAreCountedPerRequestIdentity() {
        FaultInjector injector =
                FaultInjector.of(FaultConfig.of(1, FaultRule.firstTimes(AFTER, DROP, 1)));
        assertEquals("DROP", show(injector.decide(AFTER, "ACC-1", 1)));
        assertEquals("-", show(injector.decide(AFTER, "ACC-1", 1)));
        assertEquals("DROP", show(injector.decide(AFTER, "ACC-1", 2)));
        assertEquals("DROP", show(injector.decide(AFTER, "ACC-2", 1)));
        assertEquals("-", show(injector.decide(AFTER, "ACC-2", 1)));
    }

    @Test
    @DisplayName("FR-020: a rule applies only at its own fault point")
    void ruleAppliesOnlyAtItsPoint() {
        FaultInjector injector =
                FaultInjector.of(FaultConfig.of(1, FaultRule.firstTimes(BEFORE, DROP, 5)));
        assertEquals("-", show(injector.decide(AFTER, "ACC-1", 1)));
        assertEquals("-", show(injector.decide(CLIENT, "ACC-1", 1)));
        assertEquals("DROP", show(injector.decide(BEFORE, "ACC-1", 1)));
    }

    @Test
    @DisplayName("FR-020: a rule can be limited to one account and one key")
    void ruleCanBeNarrowedToAccountAndKey() {
        FaultInjector injector = FaultInjector.of(FaultConfig.of(1,
                FaultRule.firstTimes(AFTER, DROP, 1).forAccount("ACC-1").forKey(7)));
        assertEquals("-", show(injector.decide(AFTER, "ACC-1", 8)));
        assertEquals("-", show(injector.decide(AFTER, "ACC-2", 7)));
        assertEquals("DROP", show(injector.decide(AFTER, "ACC-1", 7)));

        FaultInjector accountOnly = FaultInjector.of(FaultConfig.of(1,
                FaultRule.firstTimes(AFTER, DROP, 1).forAccount("ACC-1")));
        assertEquals("DROP", show(accountOnly.decide(AFTER, "ACC-1", 3)));
        assertEquals("DROP", show(accountOnly.decide(AFTER, "ACC-1", 4)));
        assertEquals("-", show(accountOnly.decide(AFTER, "ACC-2", 3)));
    }

    @Test
    @DisplayName("FR-020: rules are checked in order and the first one that fires wins")
    void firstFiringRuleWins() {
        FaultInjector injector = FaultInjector.of(FaultConfig.of(1,
                FaultRule.firstTimes(AFTER, DROP, 1),
                FaultRule.firstTimes(AFTER, DELAY, 2)));
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            seen.add(show(injector.decide(AFTER, "ACC-1", 1)));
        }
        assertEquals(List.of("DROP", "DELAY250", "-"), seen);
    }

    @Test
    @DisplayName("FR-020: the same configuration gives identical decisions on two runs")
    void sameConfigurationGivesSameDecisions() {
        List<String> first = sweep(FaultInjector.of(mixedConfig(42)));
        List<String> second = sweep(FaultInjector.of(mixedConfig(42)));
        assertEquals(first, second);
        assertTrue(first.contains("DROP"));
        assertTrue(first.contains("DELAY250"));
        assertTrue(first.contains("-"));
    }

    @Test
    @DisplayName("FR-020: different seeds give different decisions")
    void differentSeedsGiveDifferentDecisions() {
        assertNotEquals(rateSample(1), rateSample(2));
        assertNotEquals(sweep(FaultInjector.of(mixedConfig(1))),
                sweep(FaultInjector.of(mixedConfig(2))));
    }

    @Test
    @DisplayName("FR-020: decisions do not depend on thread timing")
    void decisionsDoNotDependOnThreadTiming() throws Exception {
        FaultConfig config = mixedConfig(42);
        FaultInjector reference = FaultInjector.of(config);
        Map<String, List<String>> expected = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            expected.put("ACC-" + i, consult(reference, "ACC-" + i, i + 1, 20));
        }

        FaultInjector shared = FaultInjector.of(config);
        ExecutorService pool = Executors.newFixedThreadPool(40);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Map<String, Future<List<String>>> futures = new HashMap<>();
            for (int i = 0; i < 40; i++) {
                String account = "ACC-" + i;
                long key = i + 1;
                futures.put(account, pool.submit(() -> {
                    go.await();
                    return consult(shared, account, key, 20);
                }));
            }
            go.countDown();
            for (Map.Entry<String, Future<List<String>>> entry : futures.entrySet()) {
                assertEquals(expected.get(entry.getKey()),
                        entry.getValue().get(30, TimeUnit.SECONDS), entry.getKey());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-020: simultaneous consultations of one request are counted exactly")
    void simultaneousConsultationsAreCountedExactly() throws Exception {
        FaultInjector injector =
                FaultInjector.of(FaultConfig.of(1, FaultRule.firstTimes(AFTER, DROP, 3)));
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<FaultAction>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return injector.decide(AFTER, "ACC-1", 1);
                }));
            }
            go.countDown();
            int drops = 0;
            for (Future<Optional<FaultAction>> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                    drops++;
                }
            }
            assertEquals(3, drops);
            assertEquals(threads, injector.occurrences(AFTER, "ACC-1", 1));
            assertEquals(1, injector.trackedRequests());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-020: a probability rule fires at roughly the configured rate")
    void probabilityRuleFiresAtRoughlyTheConfiguredRate() {
        long drops = rateSample(7).stream().filter(s -> s.equals("DROP")).count();
        assertTrue(drops > 400 && drops < 600, "drops: " + drops);
    }

    @Test
    @DisplayName("FR-020: a probability of 1 always fires")
    void probabilityOneAlwaysFires() {
        FaultInjector injector = FaultInjector.of(
                FaultConfig.of(3, FaultRule.withProbability(AFTER, DROP, 1.0)));
        for (long key = 1; key <= 200; key++) {
            assertEquals("DROP", show(injector.decide(AFTER, "ACC-1", key)));
        }
    }

    @Test
    @DisplayName("FR-020: invalid rules are refused")
    void invalidRulesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> FaultRule.firstTimes(AFTER, DROP, 0));
        assertThrows(IllegalArgumentException.class, () -> FaultRule.firstTimes(AFTER, DROP, -1));
        for (double bad : new double[] {0.0, -0.1, 1.1, Double.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FaultRule.withProbability(AFTER, DROP, bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new FaultRule(AFTER, DROP, null, 0, 3, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultRule(AFTER, DROP, null, 0, 0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultRule(AFTER, DROP, null, -1, 3, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> FaultRule.firstTimes(AFTER, DROP, 1).forKey(0));
        assertThrows(NullPointerException.class, () -> FaultRule.firstTimes(null, DROP, 1));
        assertThrows(NullPointerException.class, () -> FaultRule.firstTimes(AFTER, null, 1));
        assertThrows(NullPointerException.class,
                () -> FaultRule.firstTimes(AFTER, DROP, 1).forAccount(null));
    }

    @Test
    @DisplayName("FR-020: actions describe themselves and invalid actions are refused")
    void actionsDescribeThemselves() {
        assertTrue(DROP.isDrop());
        assertFalse(DROP.isDelay());
        assertTrue(DELAY.isDelay());
        assertEquals(250, DELAY.delayMillis());
        assertEquals(Duration.ofMillis(250), DELAY.delay());
        assertThrows(IllegalArgumentException.class,
                () -> FaultAction.delay(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultAction(FaultAction.Kind.DROP, 5));
    }

    @Test
    @DisplayName("FR-020: a configuration keeps its own copy of the rules")
    void configurationKeepsItsOwnCopyOfTheRules() {
        FaultRule rule = FaultRule.firstTimes(AFTER, DROP, 1);
        List<FaultRule> rules = new ArrayList<>(List.of(rule));
        FaultConfig config = new FaultConfig(1, rules);
        rules.clear();
        assertEquals(1, config.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> config.rules().add(rule));
    }
}