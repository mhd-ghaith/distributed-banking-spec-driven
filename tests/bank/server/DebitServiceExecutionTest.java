package bank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.common.DebitRequest;
import bank.common.InsufficientFundsException;
import bank.common.Outcome;
import bank.common.Sqlite;
import bank.common.StaleKeyException;
import bank.common.StorageException;
import bank.server.AccountStore.Account;
import bank.server.DebitService.Result;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Execution of a debit: FR-010, FR-011, FR-021. */
class DebitServiceExecutionTest {

    @TempDir
    Path directory;

    private Sqlite database;
    private AccountStore accounts;
    private DebitService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new Sqlite(directory.resolve("bank.db"), Duration.ofSeconds(2));
        accounts = new AccountStore(database);
        service = new DebitService(database, accounts);
    }

    private Account state(String id) throws Exception {
        return accounts.read(id).orElseThrow();
    }

    private void runSql(String sql) throws Exception {
        try (Connection connection = database.open();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    @DisplayName("FR-010: a new key with enough funds reduces the balance and sets the key together")
    void successChangesBalanceAndKeyTogether() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        Result result = service.execute(new DebitRequest("ACC-1", 300, 1, false));
        assertEquals(new Result(Outcome.SUCCESS, 700), result);
        assertEquals(new Account("ACC-1", 700, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-012: repeating an executed request ten times causes no further debit")
    void repeatsAreDuplicates() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        assertEquals(new Result(Outcome.SUCCESS, 700),
                service.execute(new DebitRequest("ACC-1", 300, 1, false)));
        for (int i = 0; i < 10; i++) {
            assertEquals(new Result(Outcome.DUPLICATE_ACKNOWLEDGED, 700),
                    service.execute(new DebitRequest("ACC-1", 300, 1, true)));
        }
        assertEquals(new Account("ACC-1", 700, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-011: a key that skips values is accepted, and an older key is then stale")
    void gappedKeyIsAccepted() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        service.execute(new DebitRequest("ACC-1", 100, 1, false));
        assertEquals(new Result(Outcome.SUCCESS, 800),
                service.execute(new DebitRequest("ACC-1", 100, 4, false)));
        assertEquals(new Account("ACC-1", 800, 4), state("ACC-1"));
        assertThrows(StaleKeyException.class,
                () -> service.execute(new DebitRequest("ACC-1", 100, 2, false)));
        assertEquals(new Account("ACC-1", 800, 4), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: the whole balance can be debited, and the account is then empty")
    void wholeBalanceCanBeDebited() throws Exception {
        accounts.createAccount("ACC-1", 500);
        assertEquals(new Result(Outcome.SUCCESS, 0),
                service.execute(new DebitRequest("ACC-1", 500, 1, false)));
        assertEquals(new Account("ACC-1", 0, 1), state("ACC-1"));
        assertThrows(InsufficientFundsException.class,
                () -> service.execute(new DebitRequest("ACC-1", 1, 2, false)));
    }

    @Test
    @DisplayName("FR-021: insufficient funds does not consume the key, so a smaller debit "
            + "with the same key succeeds")
    void insufficientFundsDoesNotConsumeTheKey() throws Exception {
        accounts.createAccount("ACC-1", 100);
        assertThrows(InsufficientFundsException.class,
                () -> service.execute(new DebitRequest("ACC-1", 500, 1, false)));
        assertEquals(new Account("ACC-1", 100, 0), state("ACC-1"));
        assertEquals(new Result(Outcome.SUCCESS, 40),
                service.execute(new DebitRequest("ACC-1", 60, 1, false)));
        assertEquals(new Account("ACC-1", 40, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: increasing keys are each applied once, in order")
    void sequentialDebits() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        for (long key = 1; key <= 5; key++) {
            assertEquals(new Result(Outcome.SUCCESS, 1000 - 100 * key),
                    service.execute(new DebitRequest("ACC-1", 100, key, false)));
        }
        assertEquals(new Account("ACC-1", 500, 5), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: a failure inside the update leaves balance and key both unchanged, "
            + "and the next request works")
    void failureInsideTheUpdateLeavesNothingBehind() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        runSql("CREATE TRIGGER block_updates BEFORE UPDATE ON accounts "
                + "BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");

        assertThrows(StorageException.class,
                () -> service.execute(new DebitRequest("ACC-1", 300, 1, false)));
        assertEquals(new Account("ACC-1", 1000, 0), state("ACC-1"));

        runSql("DROP TRIGGER block_updates");
        assertEquals(new Result(Outcome.SUCCESS, 700),
                service.execute(new DebitRequest("ACC-1", 300, 1, false)));
        assertEquals(new Account("ACC-1", 700, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-010: an update that changes no row is a storage error and changes nothing")
    void updateThatChangesNoRowIsAnError() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        runSql("CREATE TRIGGER skip_updates BEFORE UPDATE ON accounts "
                + "BEGIN SELECT RAISE(IGNORE); END");

        StorageException failure = assertThrows(StorageException.class,
                () -> service.execute(new DebitRequest("ACC-1", 300, 1, false)));
        assertTrue(failure.getMessage().contains("0 rows"), failure.getMessage());
        assertEquals(new Account("ACC-1", 1000, 0), state("ACC-1"));
    }
}