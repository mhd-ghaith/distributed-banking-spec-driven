package bank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.common.BankException;
import bank.common.DebitRequest;
import bank.common.InsufficientFundsException;
import bank.common.InvalidRequestException;
import bank.common.Outcome;
import bank.common.Sqlite;
import bank.common.StaleKeyException;
import bank.server.AccountStore.Account;
import bank.server.DebitService.Result;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rules that never change state: FR-012, FR-013, FR-014, FR-017, FR-021. */
class DebitServiceTest {

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

    /** Creates an account and records one executed debit, bypassing the service. */
    private void executed(String id, long start, long amount, long key) throws Exception {
        accounts.createAccount(id, start);
        int changed = database.inTransaction(c -> accounts.applyDebit(c, id, amount, key));
        assertEquals(1, changed);
    }

    private Account state(String id) throws Exception {
        return accounts.read(id).orElseThrow();
    }

    /** Runs a request and describes the result as text, so outcomes can be compared. */
    private String describe(DebitRequest request) {
        try {
            Result result = service.execute(request);
            return result.outcome() + ":" + result.balance();
        } catch (InsufficientFundsException e) {
            return "INSUFFICIENT_FUNDS:" + e.balance();
        } catch (StaleKeyException e) {
            return "STALE_KEY:" + e.balance();
        } catch (BankException e) {
            return e.getClass().getSimpleName();
        }
    }

    @Test
    @DisplayName("FR-017: an unknown account is an invalid request and nothing is created")
    void unknownAccount() throws Exception {
        assertThrows(InvalidRequestException.class,
                () -> service.execute(new DebitRequest("NOPE", 100, 1, false)));
        assertFalse(accounts.read("NOPE").isPresent());
    }

    @Test
    @DisplayName("FR-017: invalid requests are rejected without changing the account")
    void invalidRequestsAreRejectedWithoutChange() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        DebitRequest[] bad = {
                new DebitRequest("ACC-1", 0, 1, false),
                new DebitRequest("ACC-1", -5, 1, false),
                new DebitRequest("ACC-1", 100, 0, false),
                new DebitRequest("ACC-1", 100, -1, false),
                new DebitRequest("ACC 1", 100, 1, false),
                new DebitRequest(null, 100, 1, false)
        };
        for (DebitRequest request : bad) {
            assertThrows(InvalidRequestException.class, () -> service.execute(request),
                    request.toString());
        }
        assertEquals(new Account("ACC-1", 1000, 0), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-012: an equal key is acknowledged with the current balance and nothing changes")
    void equalKeyIsADuplicate() throws Exception {
        executed("ACC-1", 1000, 300, 1);
        Result result = service.execute(new DebitRequest("ACC-1", 300, 1, true));
        assertEquals(new Result(Outcome.DUPLICATE_ACKNOWLEDGED, 700), result);
        assertEquals(new Account("ACC-1", 700, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-021: a retry of an executed debit is acknowledged even though the balance "
            + "has dropped below its amount")
    void duplicateIsCheckedBeforeFunds() throws Exception {
        executed("ACC-1", 1000, 800, 1);
        Result result = service.execute(new DebitRequest("ACC-1", 800, 1, true));
        assertEquals(new Result(Outcome.DUPLICATE_ACKNOWLEDGED, 200), result);
        assertEquals(new Account("ACC-1", 200, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-012: a duplicate does not look at the amount")
    void duplicateIgnoresTheAmount() throws Exception {
        executed("ACC-1", 1000, 300, 1);
        Result result = service.execute(new DebitRequest("ACC-1", 5000, 1, true));
        assertEquals(new Result(Outcome.DUPLICATE_ACKNOWLEDGED, 700), result);
        assertEquals(new Account("ACC-1", 700, 1), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-013: the retry indicator never changes the outcome")
    void retryIndicatorNeverChangesTheOutcome() throws Exception {
        executed("ACC-1", 1000, 800, 5);
        long[][] cases = {{5, 800}, {3, 50}, {6, 500}};
        String[] expected = {"DUPLICATE_ACKNOWLEDGED:200", "STALE_KEY:200", "INSUFFICIENT_FUNDS:200"};
        for (int i = 0; i < cases.length; i++) {
            long key = cases[i][0];
            long amount = cases[i][1];
            assertEquals(expected[i], describe(new DebitRequest("ACC-1", amount, key, false)));
            assertEquals(expected[i], describe(new DebitRequest("ACC-1", amount, key, true)));
        }
        assertEquals(new Account("ACC-1", 200, 5), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-014: a lower key is stale and nothing changes")
    void lowerKeyIsStale() throws Exception {
        executed("ACC-1", 1000, 300, 5);
        StaleKeyException stale = assertThrows(StaleKeyException.class,
                () -> service.execute(new DebitRequest("ACC-1", 100, 3, false)));
        assertEquals(700, stale.balance());
        assertEquals(5, stale.lastExecutedKey());
        assertEquals(new Account("ACC-1", 700, 5), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-021: a stale key is reported even when the amount also exceeds the balance")
    void staleIsCheckedBeforeFunds() throws Exception {
        executed("ACC-1", 1000, 300, 5);
        assertThrows(StaleKeyException.class,
                () -> service.execute(new DebitRequest("ACC-1", 9999, 3, false)));
        assertEquals(new Account("ACC-1", 700, 5), state("ACC-1"));
    }

    @Test
    @DisplayName("FR-017: an amount above the balance is rejected and the key is not consumed")
    void insufficientFunds() throws Exception {
        accounts.createAccount("ACC-1", 100);
        InsufficientFundsException rejection = assertThrows(InsufficientFundsException.class,
                () -> service.execute(new DebitRequest("ACC-1", 500, 1, false)));
        assertEquals(100, rejection.balance());
        assertEquals(500, rejection.requestedAmount());
        assertEquals(new Account("ACC-1", 100, 0), state("ACC-1"));
    }
}