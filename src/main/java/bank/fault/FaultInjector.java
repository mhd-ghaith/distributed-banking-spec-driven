package bank.fault;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether a fault is injected for a request at a fault point (plan section 9, spec
 * FR-020, FR-024).
 *
 * <p>A disabled injector (the default, see {@link #disabled()}) always answers "no fault" and
 * touches no state, so behavior is identical to a system without fault injection.
 *
 * <p>An enabled injector counts how often it has been asked about each request identity (fault
 * point, account, key) and checks its rules in order. The decision of a probability rule is a
 * pure function of the seed, the request identity, the occurrence number and the rule's
 * position, so it does not depend on thread timing: the same configuration gives the same
 * decisions on every run (decision D-7).
 *
 * <p>Shared mutable state (constitution principle 5): only the occurrence counters, held in a
 * {@link ConcurrentHashMap} of {@link AtomicInteger}. Everything else is immutable.
 */
public final class FaultInjector {

    private record Tracked(FaultPoint point, String accountId, long key) {
    }

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private static final FaultInjector DISABLED = new FaultInjector(null);

    private final FaultConfig config;
    private final ConcurrentHashMap<Tracked, AtomicInteger> counters = new ConcurrentHashMap<>();

    private FaultInjector(FaultConfig config) {
        this.config = config;
    }

    /** The injector that never injects anything. */
    public static FaultInjector disabled() {
        return DISABLED;
    }

    public static FaultInjector of(FaultConfig config) {
        return new FaultInjector(Objects.requireNonNull(config, "config"));
    }

    public boolean isEnabled() {
        return config != null;
    }

    /**
     * Asks whether to inject a fault now. Each call counts as one occurrence of the request
     * identity (point, account, key). The caller performs the returned action.
     */
    public Optional<FaultAction> decide(FaultPoint point, String accountId, long key) {
        if (config == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(accountId, "accountId");

        int occurrence = counters
                .computeIfAbsent(new Tracked(point, accountId, key), tracked -> new AtomicInteger())
                .incrementAndGet();

        List<FaultRule> rules = config.rules();
        for (int index = 0; index < rules.size(); index++) {
            FaultRule rule = rules.get(index);
            if (!rule.matches(point, accountId, key)) {
                continue;
            }
            boolean fires = rule.firstOccurrences() > 0
                    ? occurrence <= rule.firstOccurrences()
                    : draw(config.seed(), point, accountId, key, occurrence, index)
                    < rule.probability();
            if (fires) {
                return Optional.of(rule.action());
            }
        }
        return Optional.empty();
    }

    /** How many times the injector has been asked about this request identity. */
    public int occurrences(FaultPoint point, String accountId, long key) {
        AtomicInteger counter = counters.get(new Tracked(point, accountId, key));
        return counter == null ? 0 : counter.get();
    }

    /** How many distinct request identities have been counted. 0 for a disabled injector. */
    public int trackedRequests() {
        return counters.size();
    }

    /** A number in [0, 1) that depends only on the arguments. */
    static double draw(long seed, FaultPoint point, String accountId, long key,
                       int occurrence, int ruleIndex) {
        long state = seed;
        state = mix(state + GOLDEN + point.ordinal());
        state = mix(state + GOLDEN + accountId.hashCode());
        state = mix(state + GOLDEN + key);
        state = mix(state + GOLDEN + occurrence);
        state = mix(state + GOLDEN + ruleIndex);
        return new SplittableRandom(state).nextDouble();
    }

    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}