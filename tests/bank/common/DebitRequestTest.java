package bank.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DebitRequestTest {

    private static DebitRequest request(String accountId, long amount, long key) {
        return new DebitRequest(accountId, amount, key, false);
    }

    @Test
    @DisplayName("FR-009: a request carries account, amount, key and retry indicator")
    void requestCarriesItsFourFields() {
        DebitRequest request = new DebitRequest("ACC-1", 300, 7, true);
        assertEquals("ACC-1", request.accountId());
        assertEquals(300, request.amount());
        assertEquals(7, request.key());
        assertTrue(request.retry());
    }

    @Test
    @DisplayName("FR-001, FR-017: a well-formed request is valid")
    void wellFormedRequestIsValid() {
        assertDoesNotThrow(() -> request("acc_1-X", 1, 1).requireValid());
    }

    @Test
    @DisplayName("FR-013: the retry indicator does not affect validity")
    void retryIndicatorDoesNotAffectValidity() {
        assertDoesNotThrow(() -> new DebitRequest("ACC-1", 5, 1, true).requireValid());
        assertDoesNotThrow(() -> new DebitRequest("ACC-1", 5, 1, false).requireValid());
    }

    @Test
    @DisplayName("FR-017: an account identifier of 64 characters is valid, 65 is not")
    void accountIdentifierLengthLimit() {
        assertDoesNotThrow(() -> request("a".repeat(64), 1, 1).requireValid());
        assertThrows(InvalidRequestException.class,
                () -> request("a".repeat(65), 1, 1).requireValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b", "acc/1", "acc.1", "acc\n1", "\u00e9"})
    @DisplayName("FR-017: an account identifier with other characters is invalid")
    void badAccountIdentifiers(String accountId) {
        assertThrows(InvalidRequestException.class, () -> request(accountId, 1, 1).requireValid());
    }

    @Test
    @DisplayName("FR-017: a missing account identifier is invalid")
    void missingAccountIdentifier() {
        assertThrows(InvalidRequestException.class, () -> request(null, 1, 1).requireValid());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    @DisplayName("FR-001, FR-017: an amount of zero or less is invalid")
    void nonPositiveAmounts(long amount) {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> request("ACC-1", amount, 1).requireValid());
        assertTrue(e.getMessage().contains("amount"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    @DisplayName("FR-017: a key of zero or less is invalid")
    void nonPositiveKeys(long key) {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> request("ACC-1", 1, key).requireValid());
        assertTrue(e.getMessage().contains("key"));
    }
}