package bank.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DebitResponseTest {

    @Test
    @DisplayName("FR-022: a response with a balance carries outcome, echo, balance and reason")
    void responseWithBalance() {
        DebitResponse response = DebitResponse.withBalance(Outcome.SUCCESS, "ACC-1", 3, 700, "");
        assertEquals(Outcome.SUCCESS, response.outcome());
        assertEquals("ACC-1", response.accountId());
        assertEquals(3, response.key());
        assertEquals(OptionalLong.of(700), response.balance());
        assertEquals("", response.reason());
    }

    @Test
    @DisplayName("FR-022: a response for an unknown account has no balance")
    void responseWithoutBalance() {
        DebitResponse response =
                DebitResponse.withoutBalance(Outcome.INVALID_REQUEST, "NOPE", 1, "unknown account");
        assertFalse(response.balance().isPresent());
        assertEquals("unknown account", response.reason());
    }

    @Test
    @DisplayName("FR-022: a response cannot be built without an outcome, identifier or reason")
    void nullsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> DebitResponse.withBalance(null, "ACC-1", 1, 0, ""));
        assertThrows(NullPointerException.class,
                () -> DebitResponse.withBalance(Outcome.SUCCESS, null, 1, 0, ""));
        assertThrows(NullPointerException.class,
                () -> DebitResponse.withBalance(Outcome.SUCCESS, "ACC-1", 1, 0, null));
        assertTrue(true);
    }
}