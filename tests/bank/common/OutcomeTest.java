package bank.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutcomeTest {

    @Test
    @DisplayName("FR-022: the six protocol outcomes exist with the names used on the wire")
    void outcomeNamesMatchTheSpecification() {
        List<String> names = Arrays.stream(Outcome.values()).map(Enum::name).toList();
        assertEquals(List.of("SUCCESS", "DUPLICATE_ACKNOWLEDGED", "INSUFFICIENT_FUNDS",
                "INVALID_REQUEST", "STALE_KEY", "ERROR"), names);
    }

    @Test
    @DisplayName("FR-022: every outcome except ERROR is definitive")
    void onlyErrorIsNonDefinitive() {
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcome != Outcome.ERROR, outcome.isDefinitive(), outcome.name());
        }
    }
}