package bank.fault;

import java.util.List;

/** A seed and an ordered list of rules. The first rule that matches and fires decides. */
public record FaultConfig(long seed, List<FaultRule> rules) {

    public FaultConfig {
        rules = List.copyOf(rules);
    }

    public static FaultConfig of(long seed, FaultRule... rules) {
        return new FaultConfig(seed, List.of(rules));
    }
}