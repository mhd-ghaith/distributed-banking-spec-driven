package bank.fault;

import java.util.Objects;

/**
 * One rule of a fault configuration. A rule names a fault point and an action, may be narrowed
 * to one account and one key, and fires in exactly one of two ways:
 * <ul>
 *   <li>on the first {@code firstOccurrences} occurrences of a request identity, or</li>
 *   <li>with the given probability, decided by a reproducible function of the seed.</li>
 * </ul>
 * An occurrence is one consultation of the injector for the same fault point, account and key,
 * counted from 1. Immutable.
 *
 * @param accountId        the account this rule is limited to, or null for any account
 * @param key              the key this rule is limited to, or 0 for any key (keys are positive)
 * @param firstOccurrences how many leading occurrences fire, or 0 if the rule uses a probability
 * @param probability      chance from just above 0 up to 1, or 0 if the rule uses occurrences
 */
public record FaultRule(FaultPoint point, FaultAction action, String accountId, long key,
                        int firstOccurrences, double probability) {

    public FaultRule {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(action, "action");
        if (key < 0) {
            throw new IllegalArgumentException("key must not be negative");
        }
        if (firstOccurrences < 0) {
            throw new IllegalArgumentException("firstOccurrences must not be negative");
        }
        if (Double.isNaN(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("probability must be between 0 and 1");
        }
        boolean byCount = firstOccurrences > 0;
        boolean byChance = probability > 0;
        if (byCount == byChance) {
            throw new IllegalArgumentException(
                    "a rule needs exactly one trigger: first N occurrences or a probability");
        }
    }

    /** A rule that fires on the first {@code times} occurrences of each request identity. */
    public static FaultRule firstTimes(FaultPoint point, FaultAction action, int times) {
        if (times <= 0) {
            throw new IllegalArgumentException("times must be greater than zero");
        }
        return new FaultRule(point, action, null, 0, times, 0);
    }

    /** A rule that fires with the given probability, greater than 0 and at most 1. */
    public static FaultRule withProbability(FaultPoint point, FaultAction action,
                                            double probability) {
        return new FaultRule(point, action, null, 0, 0, probability);
    }

    /** The same rule, limited to one account. */
    public FaultRule forAccount(String account) {
        Objects.requireNonNull(account, "account");
        return new FaultRule(point, action, account, key, firstOccurrences, probability);
    }

    /** The same rule, limited to one key. */
    public FaultRule forKey(long onlyKey) {
        if (onlyKey <= 0) {
            throw new IllegalArgumentException("key must be greater than zero");
        }
        return new FaultRule(point, action, accountId, onlyKey, firstOccurrences, probability);
    }

    boolean matches(FaultPoint candidatePoint, String candidateAccount, long candidateKey) {
        return point == candidatePoint
                && (accountId == null || accountId.equals(candidateAccount))
                && (key == 0 || key == candidateKey);
    }
}