package bank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.common.InvalidRequestException;
import bank.common.Sqlite;
import bank.common.StorageException;
import bank.server.AccountStore.Account;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {

    @TempDir
    Path directory;

    private Sqlite database;
    private AccountStore store;

    @BeforeEach
    void setUp() throws Exception {
        database = new Sqlite(directory.resolve("bank.db"), Duration.ofSeconds(2));
        store = new AccountStore(database);
    }

    /** Applies the guarded update in its own transaction and returns the changed row count. */
    private int debit(String id, long amount, long key) throws Exception {
        return database.inTransaction(connection -> store.applyDebit(connection, id, amount, key));
    }

    private Account account(String id) throws Exception {
        return store.read(id).orElseThrow();
    }

    @Test
    @DisplayName("FR-002: a new account has the given balance and last key 0")
    void newAccountStartsAtKeyZero() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-002: an unknown account is absent")
    void unknownAccountIsAbsent() throws Exception {
        assertFalse(store.read("NOPE").isPresent());
    }

    @Test
    @DisplayName("FR-002: creating an existing account fails and changes nothing")
    void duplicateCreationFails() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertThrows(StorageException.class, () -> store.createAccount("ACC-1", 5));
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-001, FR-002: an account with a negative balance cannot be created")
    void negativeInitialBalanceIsRejected() throws Exception {
        assertThrows(StorageException.class, () -> store.createAccount("ACC-1", -1));
        assertFalse(store.read("ACC-1").isPresent());
    }

    @Test
    @DisplayName("FR-001, FR-002: the table itself rejects a negative balance or last key")
    void tableRejectsNegativeValues() throws Exception {
        store.createAccount("ACC-1", 1000);
        try (Connection connection = database.open();
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE accounts SET balance = -1 WHERE id = 'ACC-1'"));
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE accounts SET last_key = -1 WHERE id = 'ACC-1'"));
        }
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: an allowed debit changes balance and key together, in one row")
    void allowedDebitChangesBalanceAndKeyTogether() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(1, debit("ACC-1", 300, 1));
        assertEquals(new Account("ACC-1", 700, 1), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-012: an equal key changes nothing")
    void equalKeyChangesNothing() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(1, debit("ACC-1", 300, 1));
        assertEquals(0, debit("ACC-1", 300, 1));
        assertEquals(new Account("ACC-1", 700, 1), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-014: a lower key changes nothing")
    void lowerKeyChangesNothing() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(1, debit("ACC-1", 300, 5));
        assertEquals(0, debit("ACC-1", 100, 3));
        assertEquals(new Account("ACC-1", 700, 5), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-017: an amount above the balance changes nothing and does not use the key")
    void insufficientFundsChangesNothing() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(0, debit("ACC-1", 1001, 1));
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-001: the whole balance can be debited, leaving zero")
    void wholeBalanceCanBeDebited() throws Exception {
        store.createAccount("ACC-1", 500);
        assertEquals(1, debit("ACC-1", 500, 1));
        assertEquals(new Account("ACC-1", 0, 1), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-011: a key that skips values is applied")
    void gappedKeyIsApplied() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(1, debit("ACC-1", 100, 1));
        assertEquals(1, debit("ACC-1", 100, 4));
        assertEquals(new Account("ACC-1", 800, 4), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-002: a debit changes only the named account")
    void otherAccountsAreUntouched() throws Exception {
        store.createAccount("ACC-1", 1000);
        store.createAccount("ACC-2", 2000);
        assertEquals(1, debit("ACC-1", 300, 1));
        assertEquals(new Account("ACC-2", 2000, 0), account("ACC-2"));
    }

    @Test
    @DisplayName("FR-017: a debit for an unknown account changes zero rows")
    void unknownAccountChangesNothing() throws Exception {
        assertEquals(0, debit("NOPE", 100, 1));
        assertFalse(store.read("NOPE").isPresent());
    }

    @Test
    @DisplayName("FR-001: an amount or key of zero or less is refused before any change")
    void nonPositiveAmountOrKeyIsRefused() throws Exception {
        store.createAccount("ACC-1", 1000);
        for (long bad : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> debit("ACC-1", bad, 1));
            assertThrows(IllegalArgumentException.class, () -> debit("ACC-1", 100, bad));
        }
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: an update rolled back with its transaction leaves balance and key "
            + "both unchanged")
    void rollbackUndoesBothChanges() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertThrows(InvalidRequestException.class, () -> database.inTransaction(connection -> {
            assertEquals(1, store.applyDebit(connection, "ACC-1", 300, 1));
            assertEquals(new Account("ACC-1", 700, 1),
                    store.find(connection, "ACC-1").orElseThrow());
            throw new InvalidRequestException("simulated failure after the update");
        }));
        assertEquals(new Account("ACC-1", 1000, 0), account("ACC-1"));
    }

    @Test
    @DisplayName("FR-019: reopening the same file keeps the table and its data")
    void tableAndDataSurviveReopening() throws Exception {
        store.createAccount("ACC-1", 1000);
        assertEquals(1, debit("ACC-1", 300, 1));

        Sqlite reopened = new Sqlite(directory.resolve("bank.db"), Duration.ofSeconds(2));
        AccountStore again = new AccountStore(reopened);
        assertEquals(new Account("ACC-1", 700, 1), again.read("ACC-1").orElseThrow());
    }
}