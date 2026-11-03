package bank.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Constitution principle 6: distinct, explicit error types. */
class BankExceptionTest {

    @Test
    @DisplayName("Principle 6: each error kind is its own type")
    void errorKindsAreDistinctTypes() {
        RuntimeException cause = new RuntimeException("cause");
        List<BankException> all = List.of(
                new InvalidRequestException("bad"),
                new InsufficientFundsException(100, 500),
                new StaleKeyException(100, 5),
                new StorageException("disk", cause),
                new NetworkFailureException("down", cause),
                new AttemptTimeoutException("slow", cause),
                new PendingTransactionException("ACC-1", 4));
        assertEquals(7, all.stream().map(Object::getClass).distinct().count());
    }

    @Test
    @DisplayName("FR-017: insufficient funds carries the balance and the requested amount")
    void insufficientFundsCarriesItsData() {
        InsufficientFundsException e = new InsufficientFundsException(100, 500);
        assertEquals(100, e.balance());
        assertEquals(500, e.requestedAmount());
    }

    @Test
    @DisplayName("FR-014: a stale key error carries the balance and the last executed key")
    void staleKeyCarriesItsData() {
        StaleKeyException e = new StaleKeyException(700, 5);
        assertEquals(700, e.balance());
        assertEquals(5, e.lastExecutedKey());
    }

    @Test
    @DisplayName("FR-005: a pending transaction error names the account and the key")
    void pendingTransactionCarriesItsData() {
        PendingTransactionException e = new PendingTransactionException("ACC-1", 4);
        assertEquals("ACC-1", e.accountId());
        assertEquals(4, e.pendingKey());
        assertTrue(e.getMessage().contains("ACC-1") && e.getMessage().contains("4"));
    }

    @Test
    @DisplayName("Principle 6: a storage error keeps its cause")
    void storageErrorKeepsItsCause() {
        RuntimeException cause = new RuntimeException("cause");
        assertSame(cause, new StorageException("disk", cause).getCause());
    }
}