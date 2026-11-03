package bank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import bank.common.BankException;
import bank.common.DebitRequest;
import bank.common.InsufficientFundsException;
import bank.common.Sqlite;
import bank.common.StaleKeyException;
import bank.server.AccountStore.Account;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

/**
 * Simultaneous requests against one database file: FR-015, FR-016. Every test is repeated 20
 * times because a race shows up only on some runs.
 */
class DebitServiceConcurrencyTest {

    private static final int REPETITIONS = 20;

    @TempDir
    Path directory;

    private AccountStore accounts;
    private DebitService service;

    @BeforeEach
    void setUp() throws Exception {
        Sqlite database = new Sqlite(directory.resolve("bank.db"), Duration.ofSeconds(30));
        accounts = new AccountStore(database);
        service = new DebitService(database, accounts);
    }

    private record Attempt(String account, long key, String result) {
    }

    /** Decides one request. Any error other than a normal rejection fails the test. */
    private Attempt attempt(DebitRequest request) throws BankException {
        String result;
        try {
            result = service.execute(request).outcome().name();
        } catch (StaleKeyException e) {
            result = "STALE_KEY";
        } catch (InsufficientFundsException e) {
            result = "INSUFFICIENT_FUNDS";
        }
        return new Attempt(request.accountId(), request.key(), result);
    }

    /** Runs all requests on separate threads, released at the same moment. */
    private List<Attempt> runTogether(List<DebitRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Attempt>> futures = new ArrayList<>();
            for (DebitRequest request : requests) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return attempt(request);
                }));
            }
            go.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Attempt> attempts, String result) {
        return attempts.stream().filter(a -> a.result().equals(result)).count();
    }

    @RepeatedTest(REPETITIONS)
    @DisplayName("FR-015: 50 simultaneous requests with the same key cause exactly one debit")
    void simultaneousRequestsWithOneKey() throws Exception {
        accounts.createAccount("ACC-1", 1000);
        List<DebitRequest> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            requests.add(new DebitRequest("ACC-1", 300, 1, i > 0));
        }

        List<Attempt> attempts = runTogether(requests);

        assertEquals(1, count(attempts, "SUCCESS"));
        assertEquals(49, count(attempts, "DUPLICATE_ACKNOWLEDGED"));
        assertEquals(new Account("ACC-1", 700, 1), accounts.read("ACC-1").orElseThrow());
    }

    @RepeatedTest(REPETITIONS)
    @DisplayName("FR-015: simultaneous requests with different keys never overdraw the account "
            + "and leave a consistent balance and key")
    void simultaneousRequestsWithDifferentKeys() throws Exception {
        accounts.createAccount("ACC-1", 100);
        List<DebitRequest> requests = new ArrayList<>();
        for (long key = 1; key <= 20; key++) {
            requests.add(new DebitRequest("ACC-1", 10, key, false));
        }

        List<Attempt> attempts = runTogether(requests);
        Account state = accounts.read("ACC-1").orElseThrow();

        List<Attempt> successes =
                attempts.stream().filter(a -> a.result().equals("SUCCESS")).toList();
        assertFalse(successes.isEmpty(), "the first request to run must succeed");
        assertTrue(state.balance() >= 0, "balance went negative: " + state);
        assertEquals(100 - 10L * successes.size(), state.balance());
        long highest = successes.stream().mapToLong(Attempt::key).max().orElseThrow();
        assertEquals(highest, state.lastKey());

        for (Attempt a : attempts) {
            switch (a.result()) {
                case "SUCCESS" -> { }
                case "STALE_KEY" -> assertTrue(a.key() < state.lastKey(), a.toString());
                case "INSUFFICIENT_FUNDS" -> assertEquals(0, state.balance(), a.toString());
                default -> fail("unexpected outcome " + a);
            }
        }
    }

    @RepeatedTest(REPETITIONS)
    @DisplayName("FR-016: simultaneous requests spread over ten accounts each end correct")
    void simultaneousRequestsAcrossManyAccounts() throws Exception {
        List<DebitRequest> requests = new ArrayList<>();
        for (int a = 0; a < 10; a++) {
            String id = "ACC-" + a;
            accounts.createAccount(id, 1000 + 10L * a);
            for (int copy = 0; copy < 5; copy++) {
                requests.add(new DebitRequest(id, 100 + a, 1, copy > 0));
            }
        }
        Collections.shuffle(requests, new Random(7));

        List<Attempt> attempts = runTogether(requests);

        for (int a = 0; a < 10; a++) {
            String id = "ACC-" + a;
            List<Attempt> mine = attempts.stream().filter(x -> x.account().equals(id)).toList();
            assertEquals(1, count(mine, "SUCCESS"), id);
            assertEquals(4, count(mine, "DUPLICATE_ACKNOWLEDGED"), id);
            assertEquals(new Account(id, 900 + 9L * a, 1), accounts.read(id).orElseThrow(), id);
        }
    }
}