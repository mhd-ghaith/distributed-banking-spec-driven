package bank.fault;

import java.time.Duration;
import java.util.Objects;

/**
 * What happens when a fault fires (plan section 9.1): DROP the message, or DELAY it. The injector
 * only decides. The caller performs the drop or sleeps for the delay.
 */
public record FaultAction(Kind kind, long delayMillis) {

    public enum Kind { DROP, DELAY }

    public FaultAction {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.DROP && delayMillis != 0) {
            throw new IllegalArgumentException("a drop has no delay");
        }
        if (kind == Kind.DELAY && delayMillis < 0) {
            throw new IllegalArgumentException("a delay must not be negative");
        }
    }

    public static FaultAction drop() {
        return new FaultAction(Kind.DROP, 0);
    }

    public static FaultAction delay(Duration delay) {
        return new FaultAction(Kind.DELAY, delay.toMillis());
    }

    public boolean isDrop() {
        return kind == Kind.DROP;
    }

    public boolean isDelay() {
        return kind == Kind.DELAY;
    }

    public Duration delay() {
        return Duration.ofMillis(delayMillis);
    }
}