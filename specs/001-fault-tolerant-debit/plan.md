# Implementation Plan: Fault-Tolerant Distributed Debit

**Feature directory**: specs/001-fault-tolerant-debit
**Created**: 2026-11-03
**Spec**: specs/001-fault-tolerant-debit/spec.md (FR-001 to FR-025)
**Governed by**: .specify/memory/constitution.md v1.0.0

## 1. Summary

A client library (the Order Service side) and an HTTP server (the Banking Service side) implement exactly-once debits on top of at-least-once delivery. The client retries a transaction with the same sequence key until it gets a definitive answer. The server decides in one SQLite transaction whether the key is new (debit and record the key), already executed (acknowledge only), or stale (reject). A fault injector can drop or delay messages at three points so that all four failure scenarios in the spec can be reproduced exactly.

## 2. Technical Context

| Item | Decision |
|---|---|
| Language and target | Java 21 (Maven `release` 21, no newer language features or APIs), developed and built with OpenJDK 27 |
| Build | Maven. Main sources in `src/main/java`, tests in `tests/` (Maven test source directory set to `tests`) |
| Dependencies | `org.xerial:sqlite-jdbc` (SQLite driver), JUnit Jupiter 5 (tests). Exact versions are pinned in `pom.xml` at implementation time. No JSON library (see decision D-4). No other dependency |
| HTTP | Server: `com.sun.net.httpserver.HttpServer`. Client: `java.net.http.HttpClient` |
| Storage | Two SQLite database files: one for the server (accounts), one for the client (key sequence and pending transaction) |
| Testing | JUnit 5, run with `mvn test` |
| Platform | Windows 11 development machine, any JDK 21+ for running |
| Scale | Single server process, single client process, tens of accounts, hundreds to a few thousand transactions in tests |
| Performance | No numeric target. Correctness only (spec SC-001 to SC-006) |

## 3. Constitution Check

| Principle | How this plan satisfies it |
|---|---|
| 1. Stack and dependencies | Only the listed JDK classes, SQLite driver, and JUnit. No JSON library, no framework |
| 2. Layout | Production code under `src/`, tests under `tests/`, specs under `specs/` and `.specify/` |
| 3. Money | All amounts and balances are `long` minor units. The database columns are integers with non-negative checks |
| 4. Atomicity | The balance change and last-key change are one `UPDATE` statement inside one transaction (section 6) |
| 5. Thread safety | The server keeps no account state in memory. The in-memory shared state that does exist is listed and protected in section 8 |
| 6. Error handling | Distinct exception types (section 7). No empty catch blocks. Every catch either handles, translates, or rethrows |
| 7. Fault injection | Deterministic, driven by a seed and a stateless decision function (section 9) |
| 8. Testing | Every FR is covered by a named test (section 12). Tests cite requirement IDs |
| 9. Traceability | Section 11 maps FR-001 to FR-025 to components. Tasks and commits will cite FR IDs |
| 10. Process | One task at a time. Gaps found during implementation update spec or plan first |
| 11. Security | No secrets. `.gitignore` already excludes database files and `.env` |

## 4. Design Decisions

| ID | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D-1 | The database transaction is the only coordination point for an account. No in-process lock per account | A per-account `ReentrantLock` map | Constitution principle 5 requires correctness from database transactions. A lock map would be shared mutable state that must be kept consistent and does not survive a second server process |
| D-2 | Every server transaction starts with `BEGIN IMMEDIATE` | Default deferred transactions | A deferred transaction reads first and upgrades to a write lock later, which allows two requests to read the same state and one of them to fail. `BEGIN IMMEDIATE` takes the write lock before reading, so the read-decide-write sequence is serialized |
| D-3 | The duplicate check runs before the funds check (FR-021) | Funds check first | A retry of an executed debit would be rejected after the balance dropped, hiding a successful charge |
| D-4 | Wire format is plain text, one `name=value` pair per line, no JSON | JSON with a library | Four request fields and six response fields do not justify a dependency. Account identifiers are restricted to letters, digits, `_` and `-`, so no escaping is needed |
| D-5 | The client persists the new key and the pending marker in its own SQLite file **before** the first send | Keep the key counter in memory | A crash after sending but before saving would reuse a key. Writing first makes FR-004 and FR-025 hold across restarts |
| D-6 | The server returns HTTP 200 for every decided outcome and puts the outcome in the body. The client treats any other status or an unreadable body as the non-definitive ERROR outcome | Using HTTP status codes as outcomes | The protocol outcomes are the contract. HTTP errors then only ever mean transport trouble |
| D-7 | Fault decisions are a pure function of seed, fault point, account, key, and occurrence number | A shared `Random` stepped by each call | A shared generator gives different results depending on thread timing. A pure function is reproducible and needs no lock |
| D-8 | Injected delays and drops happen outside the database transaction | Delay inside the transaction | A delay inside the transaction would hold SQLite's write lock and block every other account (FR-016) |
| D-9 | Server logging uses `java.util.logging` | A logging library | No extra dependency, and the retry indicator and decisions are recorded for diagnostics (FR-013) |

## 5. Components and Source Layout

Packages live under `src/main/java/bank/`. Tests mirror them under `tests/bank/`.

| Package | Class | Responsibility |
|---|---|---|
| `bank.common` | `Outcome` | Enum: SUCCESS, DUPLICATE_ACKNOWLEDGED, INSUFFICIENT_FUNDS, INVALID_REQUEST, STALE_KEY, ERROR. Method `isDefinitive()` is false only for ERROR |
| | `DebitRequest` | Immutable record: account id, amount, key, retry indicator. Method `requireValid()` is the single place that checks the account identifier characters, a positive amount, and a positive key |
| | `DebitResponse` | Immutable record: outcome, account id, key, balance (optional), reason (optional) |
| | `WireFormat` | Encodes and decodes requests and responses. Decoding failures raise `InvalidRequestException` (server side) or are treated as ERROR (client side) |
| | `Sqlite` | Opens connections with the project's settings (WAL journal, full synchronous, busy timeout) and runs work in a transaction that starts with an explicit `BEGIN IMMEDIATE`, commits on success and rolls back on any failure. Holds only the database URL and the timeout |
| | `TransactionWork` | Functional interface for the body of a transaction. It may throw any `BankException`, which rolls the transaction back and propagates unchanged |
| | `BankException` and subclasses | See section 7 |
| `bank.server` | `BankingServer` | Starts and stops the HTTP server with a fixed thread pool. Creates the schema if missing |
| | `DebitHandler` | HTTP handler for `POST /debit`. Decodes, asks the fault injector, calls the service, encodes. Maps exceptions to outcomes |
| | `DebitService` | The decision logic of section 6. One transaction per request |
| | `AccountStore` | SQL access for accounts: read, conditional update, and a method used by test setup to create accounts |
| | `ServerConfig` | Port (0 means any free port), database path, worker thread count, busy timeout |
| | `ServerMain` | Command-line entry point to run the server |
| `bank.client` | `OrderServiceClient` | Public API: `submit`, `resumePending`, `pending`. Runs the attempt loop |
| | `ClientStateStore` | SQL access to the client's key sequence and pending transaction |
| | `RetryPolicy` | Immutable: max attempts, first backoff, multiplier, max backoff, per-attempt timeout |
| | `DebitTransport` | Interface: send one request, return one response or throw a network or timeout exception |
| | `HttpDebitTransport` | Implementation using `java.net.http.HttpClient` |
| | `TransactionResult` | Immutable: status (COMPLETED, REJECTED, OUTCOME_UNKNOWN), outcome, account, key, balance, reason |
| | `ClientMain` | Command-line entry point for a demonstration |
| `bank.fault` | `FaultInjector` | Decides, for a fault point and request, whether to drop or delay. A disabled instance does nothing |
| | `FaultConfig`, `FaultRule` | Seed and a list of rules (section 9) |
| | `FaultPoint`, `FaultAction` | Enums and value types for where and what |
| | `FaultyTransport` | Wraps a `DebitTransport`. Applies the client-side fault point |

## 6. Server Behavior

### 6.1 Data model (server database)

Table `accounts`:

| Column | Type | Constraints |
|---|---|---|
| `id` | text | primary key |
| `balance` | integer | not null, must be at least 0 |
| `last_key` | integer | not null, default 0, must be at least 0 |

No other server table is needed. The spec assumes the server's only durable memory is balance and last key.

### 6.2 Request handling sequence

1. `DebitHandler` reads the body and decodes it. A missing field, a non-numeric number, an account id outside the allowed characters, a non-positive key, or a non-positive amount produces INVALID_REQUEST before touching the database (FR-017).
2. The handler asks the fault injector about `SERVER_BEFORE_EXECUTION`. A DROP aborts the exchange with no response and no database access. A DELAY sleeps (outside any transaction).
3. `DebitService` opens a connection and begins an immediate transaction.
4. It reads the account row. If there is none, it rolls back and the outcome is INVALID_REQUEST (unknown account).
5. It applies the rules in the order required by FR-021 and stops at the first that matches:
   1. Key equal to `last_key`: roll back, outcome DUPLICATE_ACKNOWLEDGED with the current balance. The amount in the request is not compared or used, and the retry indicator is not used.
   2. Key lower than `last_key`: roll back, outcome STALE_KEY.
   3. Amount greater than the balance: roll back, outcome INSUFFICIENT_FUNDS. The key is not recorded.
   4. Otherwise: one `UPDATE` sets `balance = balance - amount` and `last_key = key` for that account, guarded by `last_key < key` and `balance >= amount`. Exactly one row must change, otherwise the transaction is rolled back and treated as ERROR. Commit. Outcome SUCCESS with the new balance.
6. After the transaction ends, the handler asks about `SERVER_AFTER_COMMIT`. DROP aborts the exchange without sending the response. DELAY sleeps before sending. This point applies to every outcome, but the four required scenarios use it after a successful commit.
7. The handler writes the response: HTTP 200 and the encoded outcome.
8. Each decision is logged with account, key, retry indicator, outcome, and attempt occurrence (FR-013).

### 6.3 Why this is correct

- Rules 1 to 3 never modify data, so a rejected or duplicate request cannot change state.
- Rule 4 changes balance and last key in one statement and one commit. After a crash, either both changed or neither did (FR-010).
- `BEGIN IMMEDIATE` makes a second request for the same account wait on SQLite's write lock until the first commits, then read the updated state. A retry that arrives during processing therefore waits and sees the executed key (FR-015).
- If the lock is not obtained within the busy timeout, the request ends with ERROR and no change, and the client retries the same key.
- SQLite allows one writer at a time for the whole database file, so transactions for different accounts are also serialized, but only for the few milliseconds of a transaction. Delays and drops happen outside transactions (D-8), so no account waits for another account's injected delay (FR-016).
- Durability: WAL journal with full synchronous mode, so a committed debit survives a process crash and a restart (FR-019).

## 7. Error Types

| Type | Thrown by | Meaning | Maps to |
|---|---|---|---|
| `BankException` | (base, sealed, checked) | Common parent. All subclasses are in `bank.common`, because a sealed class in a project without a module descriptor requires its subclasses in the same package | |
| `InvalidRequestException` | `WireFormat`, `DebitService` | Malformed request, unknown account, non-positive amount or key | INVALID_REQUEST |
| `InsufficientFundsException` | `DebitService` | Amount exceeds balance for a new key. Carries the balance | INSUFFICIENT_FUNDS |
| `StaleKeyException` | `DebitService` | Key lower than last executed. Carries the balance | STALE_KEY |
| `StorageException` | `Sqlite`, `AccountStore`, `ClientStateStore` | Database failure or lock timeout | ERROR (server). Propagates to the caller (client store) |
| `NetworkFailureException` | `DebitTransport` | Connection failed or was closed without a response | Non-definitive, retried |
| `AttemptTimeoutException` | `DebitTransport` | No response within the per-attempt timeout | Non-definitive, retried |
| `PendingTransactionException` | `OrderServiceClient` | A new debit was requested for an account with an unresolved transaction | Returned to the caller, no key issued |

Throwing the rejection exceptions inside the transaction rolls it back, which guarantees that rejected requests change nothing.

## 8. Shared In-Memory State (constitution principle 5)

| State | Where | Protection |
|---|---|---|
| Fault occurrence counters (count of requests seen per fault point, account, and key) | `FaultInjector` | A `ConcurrentHashMap` of `AtomicInteger`, created with `computeIfAbsent` and advanced with `incrementAndGet`. Used by both client and server when a test shares one injector |
| Server worker pool | `BankingServer` | Created and shut down by the JDK `ExecutorService`. Handlers keep no request state between calls |
| Server running flag | `BankingServer` | `volatile` field, changed only by `start` and `stop`, which are `synchronized` |
| Logging | `java.util.logging` | Thread-safe by the JDK. The test log collector uses a `CopyOnWriteArrayList` |
| Account balances and keys | not in memory | Only in SQLite, always inside a transaction |
| Client key counter and pending transaction | not in memory | Only in the client's SQLite file, always inside a transaction |
| `HttpClient` | `HttpDebitTransport` | Documented by the JDK as thread-safe and immutable once built |
| Configuration objects | everywhere | Immutable records |

No other mutable state is shared between threads.

## 9. Fault Injection Design

### 9.1 Fault points and actions

| Point | Where it is checked |
|---|---|
| `CLIENT_BEFORE_SEND` | In `FaultyTransport`, before the request is sent |
| `SERVER_BEFORE_EXECUTION` | In `DebitHandler`, after decoding, before the database transaction |
| `SERVER_AFTER_COMMIT` | In `DebitHandler`, after the transaction ends, before the response is written |

| Action | Effect |
|---|---|
| DROP | At the client point: the request is not sent and a `NetworkFailureException` is raised. At server points: the exchange is aborted without a response, so the client sees a closed connection |
| DELAY(milliseconds) | Sleep, then continue normally |

### 9.2 The four required scenarios

| Spec scenario | Fault point and action |
|---|---|
| S-1 Request never reaches the server | `CLIENT_BEFORE_SEND` with DROP |
| S-2 Server fails before executing | `SERVER_BEFORE_EXECUTION` with DROP |
| S-3 Executes and saves, response lost | `SERVER_AFTER_COMMIT` with DROP |
| S-4 Response after the client timeout | `SERVER_AFTER_COMMIT` with DELAY longer than the per-attempt timeout |

A further test-only combination, `SERVER_BEFORE_EXECUTION` with DELAY, makes the original request execute after its own retry, which exercises the duplicate path under real concurrency.

### 9.3 Configuration and determinism

A `FaultConfig` holds a seed and a list of rules. A rule names a point, an action, an optional account, an optional key, and either "apply to the first N occurrences" or "apply with probability p". For a probability rule, the decision is computed by seeding a fresh `SplittableRandom` from a hash of the seed, the point, the account, the key, and the occurrence number. The same inputs always give the same decision, whatever the thread timing (D-7, FR-020).

A disabled injector is the default. When it is in use, no rule is evaluated, no counter is touched, and behavior is identical to a build without fault injection (FR-024).

## 10. Client Behavior

### 10.1 Data model (client database)

Table `client_accounts`:

| Column | Type | Meaning |
|---|---|---|
| `account_id` | text, primary key | The account |
| `last_key` | integer, not null | The highest key ever issued for the account |
| `pending_amount` | integer, nullable | Set while the transaction using `last_key` is unresolved, null otherwise |

A pending transaction exists exactly when `pending_amount` is not null, and its key is `last_key`.

### 10.2 `submit(account, amount)`

1. In one immediate transaction: if the account has a pending transaction, raise `PendingTransactionException` and issue nothing. Otherwise set `last_key` to `last_key + 1` (starting from 1) and set `pending_amount`. Commit (D-5, FR-003, FR-005).
2. Make attempt 1 with the retry indicator false, and every later attempt with it true (FR-009).
3. Each attempt calls the transport with the per-attempt timeout. A response must echo the same account and key, otherwise it is ignored as if it never arrived (FR-023).
4. A definitive outcome ends the loop: clear `pending_amount`, and return COMPLETED (SUCCESS or DUPLICATE_ACKNOWLEDGED) or REJECTED with the reason (INSUFFICIENT_FUNDS, INVALID_REQUEST, STALE_KEY) (FR-022).
5. A non-definitive result (ERROR outcome, network failure, timeout) waits for the backoff and tries again with the same key and amount (FR-006, FR-008). Backoff starts at the configured first value, multiplies after each failure, and is capped. There is no random jitter by default, so tests are reproducible.
6. When the attempt limit is reached, return OUTCOME_UNKNOWN with the key. `pending_amount` stays set, so no higher key can be issued for that account (FR-018).

### 10.3 `resumePending(account)`

Retries the pending key and amount with the same loop, with the retry indicator true on every attempt (a restarted client cannot know whether the first attempt was ever sent). A definitive outcome clears the pending state and permits new debits (FR-007, FR-025). It is also the way to continue after a client restart.

### 10.4 Notes

- Keys are consecutive on the client. A rejection with no state change leaves a gap on the server, which the server accepts (FR-011).
- Two threads submitting for the same account are serialized by the client database transaction. The second receives `PendingTransactionException`.
- A late response for an attempt that already timed out is discarded by the HTTP client and, as a second guard, by the echo check.

## 11. Requirement Traceability

| FR | Summary | Implemented in |
|---|---|---|
| FR-001 | Integer minor units, positive amount | `DebitRequest` (long), `WireFormat` validation, `accounts` column |
| FR-002 | Balance and last key, key starts at 0 | `accounts` table, `AccountStore` |
| FR-003 | Monotonic per-account client keys | `ClientStateStore` (`last_key + 1`) |
| FR-004 | Keys never regress, including after restart | `ClientStateStore` persisted before send (D-5) |
| FR-005 | One unresolved transaction per account | `pending_amount` check in `submit` |
| FR-006 | Same key on every retry | Attempt loop in `OrderServiceClient` |
| FR-007 | Higher key only after a definitive outcome | `pending_amount` cleared only on definitive outcome |
| FR-008 | At-least-once with backoff | `RetryPolicy`, attempt loop |
| FR-009 | Request fields and retry indicator | `DebitRequest`, `WireFormat`, attempt loop |
| FR-010 | Debit and key recorded atomically | `DebitService` rule 4 (single `UPDATE`, one transaction) |
| FR-011 | Gapped keys accepted | `DebitService` (any key greater than `last_key`) |
| FR-012 | Equal key gives duplicate acknowledgment | `DebitService` rule 1 |
| FR-013 | Idempotency from the key only, indicator is diagnostic | `DebitService` ignores the indicator, `DebitHandler` logs it |
| FR-014 | Lower key gives stale key | `DebitService` rule 2 |
| FR-015 | Same-account requests one at a time | `BEGIN IMMEDIATE` and busy timeout (D-2) |
| FR-016 | Different accounts independent | Short transactions, delays outside transactions (D-8) |
| FR-017 | Invalid requests rejected | `WireFormat` validation, unknown account in `DebitService` |
| FR-018 | Outcome unknown, key stays pending | `OrderServiceClient` step 6, `pending_amount` retained |
| FR-019 | State survives restarts | SQLite files, WAL with full synchronous mode |
| FR-020 | Deterministic fault injection | `bank.fault` package, pure decision function (D-7) |
| FR-021 | Evaluation order | `DebitService` rules 1 to 4 in order (D-3) |
| FR-022 | Definitive classification and reporting | `Outcome.isDefinitive()`, `TransactionResult` |
| FR-023 | Match responses, ignore stale ones | Echo check and per-attempt timeout in the client |
| FR-024 | Fault injection off by default | Disabled `FaultInjector` |
| FR-025 | Resume after client restart | `resumePending`, `ClientStateStore` |

## 12. Verification Plan

Every test name or annotation cites the requirement IDs it verifies (constitution principle 8). Tests use a temporary directory for database files and port 0 for the server.

| Test group | Covers |
|---|---|
| `WireFormatTest`: encode and decode round trips, malformed input | FR-001, FR-009, FR-017 |
| `DebitServiceTest`: new key, equal key, lower key, gap, insufficient funds, zero and negative amount, unknown account, **retry of an executed debit after the balance dropped**, unchanged state after every rejection | FR-002, FR-010 to FR-014, FR-017, FR-021 |
| `DebitServiceConcurrencyTest`: many simultaneous requests with one key, simultaneous different keys, many accounts at once | FR-015, FR-016, SC-002 |
| `ClientStateStoreTest`: key sequence, pending marker, restart (new store object on the same file) | FR-003 to FR-005, FR-007, FR-025 |
| `OrderServiceClientTest` using a scripted fake transport: retry then success, ERROR then success, exhausted retries, pending blocks new debit, `resumePending`, late or mismatched response ignored | FR-006 to FR-009, FR-018, FR-022, FR-023 |
| `FaultScenarioTest`: S-1 to S-4 over real HTTP, plus the before-execution delay combination and combined faults, each ending with the final balance and key | FR-020, FR-024, SC-001 |
| `RestartTest`: execute, stop the server, start a new one on the same file, repeat the request | FR-019, SC-005 |
| `FaultInjectorTest`: same seed gives identical decisions under concurrent use, disabled injector has no effect | FR-020, FR-024, SC-004 |
| `StressTest`: hundreds of debits over several accounts with seeded random faults, final balances equal start minus the distinct executed debits, run twice to compare | SC-001, SC-003, SC-004 |
| Coverage check: a test or script that lists every FR ID and confirms at least one test cites it | SC-006 |

## 13. Risks and Open Items

| Risk | Plan |
|---|---|
| Maven plugins or the SQLite native library may not yet support JDK 27 cleanly, and newer JDKs may print warnings about native access | Verify at the first task. If needed, add the JVM option the warning names to the test configuration. If it still fails, build with JDK 21. The code targets 21 either way, so the constitution still holds |
| Aborting an exchange to simulate a dropped response may behave differently from what the client expects | Verify with the first fault test. The only requirement is that the client sees a failed attempt with no response |
| A very short per-attempt timeout in tests can cause false failures on a slow machine | Use generous gaps between the timeout and the injected delay, and between attempts |
| The server does not store the amount of an executed debit, so it cannot detect a client that reuses a key with a different amount | Accepted by the spec assumption that keys come from one client sequence. Noted for the README |

## 14. Out of Scope

Deposits, transfers, account creation requests, authentication, currencies and fees, replication, and any user interface. Accounts are created by test setup through `AccountStore`.