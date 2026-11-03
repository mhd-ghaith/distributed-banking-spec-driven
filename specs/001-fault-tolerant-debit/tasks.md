# Tasks: Fault-Tolerant Distributed Debit

**Feature directory**: specs/001-fault-tolerant-debit
**Created**: 2026-11-03
**Inputs**: spec.md (FR-001 to FR-025), plan.md
**Governed by**: .specify/memory/constitution.md v1.0.0

## Working Rules (from the constitution, principles 8 to 10)

- Do one task at a time, in order. Do not start a task before its dependencies are done.
- A task is done only when its tests exist, name the requirement IDs they verify, and pass with `mvn test`.
- Each task is one commit. The message starts with the task ID and cites requirement IDs, for example `T007: atomic debit execution (FR-010, FR-011, FR-021)`.
- If a task reveals a gap or contradiction in `spec.md` or `plan.md`, stop. Fix and commit the document first, then continue the task.
- Production code goes under `src/main/java/bank/...`, tests under `tests/bank/...`.

## Phase 1: Setup

### T001: Maven project skeleton
- **Depends on**: none
- **Files**: `pom.xml`, `src/main/java/bank/` and `tests/bank/` folders
- **Work**: Create the Maven project with Java release level 21, the test source directory set to `tests`, the SQLite JDBC driver, and JUnit Jupiter 5 with the surefire plugin. Pin dependency versions.
- **Requirements**: constitution principles 1 and 2 (no FR)
- **Done when**: `mvn -v` shows the JDK in use, and `mvn test` finishes with BUILD SUCCESS. IntelliJ imports the project with language level 21. If the build fails because of JDK 27, apply the plan's risk mitigation and record what happened.

### T002: SQLite connection support
- **Depends on**: T001, T003 (T003 creates `StorageException`, so it is done first)
- **Files**: `src/main/java/bank/common/Sqlite.java`, `tests/bank/common/SqliteTest.java`
- **Work**: A class that opens connections to a database file with WAL journal mode, full synchronous mode, a busy timeout, and immediate transaction mode, and throws `StorageException` on failure. It holds only the database URL.
- **Requirements**: FR-019 (durability settings), FR-015 (immediate transactions)
- **Done when**: Tests show that a value written and committed in one connection is read from a second connection, that a rolled-back write is not visible, and that a connection reports the configured journal and synchronous modes.

## Phase 2: Shared types

### T003: Outcomes, messages, and error types
- **Depends on**: T001
- **Files**: `bank/common/Outcome.java`, `DebitRequest.java`, `DebitResponse.java`, `BankException.java`, `InvalidRequestException.java`, `InsufficientFundsException.java`, `StaleKeyException.java`, `StorageException.java`, `NetworkFailureException.java`, `AttemptTimeoutException.java`, `PendingTransactionException.java`, plus `tests/bank/common/OutcomeTest.java`, `DebitRequestTest.java`, `DebitResponseTest.java`, `BankExceptionTest.java`
- **Work**: Create the types from plan sections 5 and 7. `DebitRequest` and `DebitResponse` are immutable records using `long` for amounts, keys, and balances. `DebitRequest.requireValid()` is the single place that checks the account identifier (1 to 64 letters, digits, `_` or `-`), the amount (greater than zero), and the key (greater than zero). `BankException` is a sealed checked exception, and all its subclasses live in `bank.common` because the project has no module descriptor.
- **Requirements**: FR-001, FR-009, FR-017, FR-022
- **Done when**: Tests confirm the six outcome names, that `isDefinitive()` is true for SUCCESS, DUPLICATE_ACKNOWLEDGED, INSUFFICIENT_FUNDS, INVALID_REQUEST, and STALE_KEY and false for ERROR only, that `requireValid()` accepts a well-formed request and rejects each bad account identifier, amount, and key, that responses carry an optional balance, and that every error type is a distinct class carrying its data.

### T004: Wire format
- **Depends on**: T003
- **Files**: `bank/common/WireFormat.java`, `tests/bank/common/WireFormatTest.java`
- **Work**: Encode and decode requests and responses as lines of `name=value` (plan decision D-4). Decoding a request parses every field and then calls `DebitRequest.requireValid()`.
- **Requirements**: FR-001, FR-009, FR-017
- **Done when**: Tests cover round trips for requests and responses, and `InvalidRequestException` for: a missing field, a repeated field, a non-numeric number, an amount of zero or less, a key of zero or less, an account id with characters outside letters, digits, `_` and `-`, and an empty body.

## Phase 3: Server logic

### T005: Server schema and account store
- **Depends on**: T002, T003
- **Files**: `bank/server/AccountStore.java`, `tests/bank/server/AccountStoreTest.java`
- **Work**: Create the `accounts` table if missing (plan section 6.1). Provide: create an account with a balance (for test setup), read an account's balance and last key, and a guarded update that subtracts an amount and sets the key only if `last_key < key` and `balance >= amount`, reporting how many rows changed.
- **Requirements**: FR-001, FR-002
- **Done when**: Tests show that a new account has last key 0, that the table rejects a negative balance, that the guarded update changes one row when allowed and zero rows when the key is not greater or the funds are too low, and that nothing changes in the zero-row case.

### T006: Debit decisions that never change state
- **Depends on**: T005
- **Files**: `bank/server/DebitService.java` (first part), `tests/bank/server/DebitServiceTest.java`
- **Work**: The service first calls `requireValid()` on the request, then opens an immediate transaction, reads the account, and applies rules in the order from FR-021: unknown account gives `InvalidRequestException`, equal key gives DUPLICATE_ACKNOWLEDGED with the current balance (ignoring the request's amount and retry indicator), lower key gives `StaleKeyException`, amount over balance gives `InsufficientFundsException`. All of these roll back.
- **Requirements**: FR-012, FR-013, FR-014, FR-017, FR-021
- **Done when**: Tests cover each rule and assert that balance and last key are unchanged afterward. One test covers the **retry of an executed debit after the balance dropped** (balance 1000, debit 800 key 1 executed, retry of 800 key 1 gets DUPLICATE_ACKNOWLEDGED). Another test shows the retry indicator has no effect on the outcome.

### T007: Atomic debit execution
- **Depends on**: T006
- **Files**: `bank/server/DebitService.java` (second part), `DebitServiceTest.java`
- **Work**: For a key greater than the last key with enough funds, execute the guarded update and commit, returning SUCCESS with the new balance. If the update changes anything other than one row, roll back and raise a storage error.
- **Requirements**: FR-010, FR-011, FR-021
- **Done when**: Tests show SUCCESS reduces the balance and sets the key together, a key that skips values (for example 1 then 4) is accepted, a failure injected between the two changes cannot leave only one of them (verified by forcing the update to fail and checking both values are unchanged), and a rejected insufficient-funds request does not consume the key so a later smaller debit with the same key succeeds.

### T008: Concurrency on the service
- **Depends on**: T007
- **Files**: `tests/bank/server/DebitServiceConcurrencyTest.java`, fixes in `DebitService.java` or `Sqlite.java` if the tests expose problems
- **Work**: Write tests that run the service from many threads against one database file.
- **Requirements**: FR-015, FR-016
- **Done when**: Tests show that (a) 50 simultaneous requests with the same key on one account produce exactly one debit and 49 duplicate acknowledgments, (b) simultaneous requests with different increasing keys never produce a negative balance and leave the balance equal to the start minus the executed amounts, and (c) requests spread across many accounts each end with the correct balance. The tests are repeated 20 times in a loop to expose intermittent races.

## Phase 4: Fault injection

### T009: Fault injector
- **Depends on**: T003
- **Files**: `bank/fault/FaultPoint.java`, `FaultAction.java`, `FaultRule.java`, `FaultConfig.java`, `FaultInjector.java`, `tests/bank/fault/FaultInjectorTest.java`
- **Work**: Implement plan section 9: three fault points, DROP and DELAY actions, rules that match by point, optional account and key, and either "first N occurrences" or a probability. The probability decision is a pure function of seed, point, account, key, and occurrence number. Occurrence counters use a `ConcurrentHashMap` of `AtomicInteger`. A disabled injector is the default and does nothing.
- **Requirements**: FR-020, FR-024
- **Done when**: Tests show that the same configuration gives identical decisions across two runs, including when decisions are requested from many threads at once, that "first N occurrences" applies exactly N times, that different seeds give different decisions over a large sample, and that the disabled injector never produces a fault and never creates counters.

## Phase 5: HTTP server

### T010: Banking server and handler
- **Depends on**: T004, T008, T009
- **Files**: `bank/server/ServerConfig.java`, `BankingServer.java`, `DebitHandler.java`, `tests/bank/server/BankingServerTest.java`
- **Work**: Start `HttpServer` on a configurable port (0 for any free port) with a fixed thread pool. The `POST /debit` handler decodes the request, consults the injector at `SERVER_BEFORE_EXECUTION`, calls the service, consults it at `SERVER_AFTER_COMMIT`, and writes an HTTP 200 response with the encoded outcome. Map exceptions to outcomes (plan section 7), and unexpected failures to ERROR. Log each decision with account, key, retry indicator, outcome, and occurrence. Delays and drops happen outside the database transaction.
- **Requirements**: FR-009, FR-013, FR-016, FR-017, FR-020
- **Done when**: Tests send real HTTP requests to a server on a free port and confirm: a normal debit returns SUCCESS and the right balance, a repeat returns DUPLICATE_ACKNOWLEDGED, each rejection outcome is returned correctly, a malformed body returns INVALID_REQUEST, the log contains the retry indicator for a request, and a drop at each server fault point produces a request with no response and the expected database state (unchanged before execution, committed after commit).

### T011: Server restart durability
- **Depends on**: T010
- **Files**: `tests/bank/server/RestartTest.java`
- **Work**: Tests that stop the server and start a new one on the same database file.
- **Requirements**: FR-019
- **Done when**: A debit executed before the restart is not lost or repeated after it: the same request after the restart returns DUPLICATE_ACKNOWLEDGED and the balance is unchanged. A restart after a rejected request leaves the key unconsumed.

## Phase 6: Client

### T012: Client state store
- **Depends on**: T002
- **Files**: `bank/client/ClientStateStore.java`, `tests/bank/client/ClientStateStoreTest.java`
- **Work**: Implement the `client_accounts` table (plan section 10.1). One immediate transaction either issues the next key and marks the transaction pending, or refuses because one is pending. Another call clears the pending marker. Another reads the pending transaction, if any.
- **Requirements**: FR-003, FR-004, FR-005, FR-007, FR-025
- **Done when**: Tests show keys start at 1 and increase by one per transaction, a second issue for an account with a pending transaction is refused, clearing the marker allows the next key, accounts are independent, and a **new store object opened on the same file** continues the sequence and still reports the pending transaction (restart).

### T013: Retry policy and HTTP transport
- **Depends on**: T010
- **Files**: `bank/client/RetryPolicy.java`, `DebitTransport.java`, `HttpDebitTransport.java`, `tests/bank/client/HttpDebitTransportTest.java`
- **Work**: A policy record with max attempts, first backoff, multiplier, max backoff, and per-attempt timeout, and a method returning the wait before a given attempt. The transport sends one request through `HttpClient` with the timeout and returns the decoded response. A timeout raises `AttemptTimeoutException`, a connection failure or closed connection raises `NetworkFailureException`, and a non-200 status or unreadable body becomes an ERROR response.
- **Requirements**: FR-006, FR-008, FR-009
- **Done when**: Tests show the backoff sequence grows, is capped, and has no randomness. Against a real server, a normal request returns the decoded response, a server drop at the before-execution point raises `NetworkFailureException`, and a delay longer than the timeout raises `AttemptTimeoutException`.

### T014: Client submit loop
- **Depends on**: T012, T013
- **Files**: `bank/client/OrderServiceClient.java`, `TransactionResult.java`, `tests/bank/client/OrderServiceClientTest.java`
- **Work**: Implement `submit` as in plan section 10.2, using a scripted fake transport in tests so every sequence of responses is exact. The first attempt has the retry indicator false and later ones true. A response whose account or key does not match is ignored as if nothing arrived.
- **Requirements**: FR-005, FR-006, FR-007, FR-008, FR-009, FR-018, FR-022, FR-023
- **Done when**: Tests show: success on the first attempt, ERROR then success using the same key, a timeout then DUPLICATE_ACKNOWLEDGED reported as COMPLETED, INSUFFICIENT_FUNDS and INVALID_REQUEST and STALE_KEY reported as REJECTED with the reason, exhausted attempts reported as OUTCOME_UNKNOWN with the key and never as failed, a new `submit` for that account refused with `PendingTransactionException` and no new key issued, the retry indicator sequence false then true, and a mismatched response ignored.

### T015: Resume after outcome unknown and after restart
- **Depends on**: T014
- **Files**: `OrderServiceClient.java` (add `resumePending` and `pending`), `tests/bank/client/ResumeTest.java`
- **Work**: Implement `resumePending` as in plan section 10.3.
- **Requirements**: FR-007, FR-018, FR-025
- **Done when**: Tests show that after OUTCOME_UNKNOWN a later `resumePending` that gets SUCCESS or DUPLICATE_ACKNOWLEDGED clears the pending state and allows the next higher key, a client rebuilt on the same state file resumes the same key and amount, every attempt in a resume has the retry indicator true, and a rejection also resolves the pending state.

### T016: Client-side fault point
- **Depends on**: T009, T013
- **Files**: `bank/fault/FaultyTransport.java`, `tests/bank/fault/FaultyTransportTest.java`
- **Work**: A `DebitTransport` wrapper that asks the injector about `CLIENT_BEFORE_SEND`. DROP raises `NetworkFailureException` without sending anything. DELAY sleeps and then forwards.
- **Requirements**: FR-020, FR-024
- **Done when**: Tests show that a dropped request never reaches the server (the server sees no request), that a disabled injector changes nothing, and that a delay is applied before the request is forwarded.

## Phase 7: End-to-end verification

### T017: The four failure scenarios
- **Depends on**: T011, T015, T016
- **Files**: `tests/bank/e2e/FaultScenarioTest.java`
- **Work**: Real client, real server, real HTTP, temporary files. Each scenario starts with balance 1000, debit 300, key 1.
- **Requirements**: FR-020, SC-001
- **Done when**: Each test ends with balance 700 and last key 1 and the client reporting COMPLETED: **S-1** request dropped at the client, **S-2** dropped before execution, **S-3** dropped after commit (the retry gets DUPLICATE_ACKNOWLEDGED), **S-4** delayed after commit beyond the attempt timeout (the late response is ignored). Additional tests cover the delay-before-execution combination (the retry executes first, the original then gets a duplicate) and a combined sequence such as S-1 then S-3 on one transaction. Server logs are checked to confirm each fault happened.

### T018: Repetition and simultaneous retries
- **Depends on**: T017
- **Files**: `tests/bank/e2e/RepeatedRequestTest.java`
- **Work**: Send the identical request many times, sequentially and simultaneously, directly to a running server.
- **Requirements**: FR-012, FR-015, SC-002
- **Done when**: 100 sequential and 100 simultaneous copies of one request give exactly one debit, one SUCCESS and the rest DUPLICATE_ACKNOWLEDGED (or all duplicates if the first executed before the rest), and a stable final balance.

### T019: Randomized stress with faults
- **Depends on**: T017
- **Files**: `tests/bank/e2e/StressTest.java`
- **Work**: Several accounts, each driven by its own client thread, several hundred debits in total, seeded random faults at all three points with a retry policy generous enough to finish. Keep a record of each transaction's final outcome.
- **Requirements**: FR-020, SC-001, SC-003, SC-004
- **Done when**: Every account's final balance equals its start minus the sum of the amounts of its transactions that were COMPLETED, plus the amounts of any unresolved ones that the server actually executed (checked by resuming them). Running the same workload and seed twice produces the same final balances. The test is run 20 times without failure.

## Phase 8: Delivery

### T020: Command-line entry points
- **Depends on**: T015
- **Files**: `bank/server/ServerMain.java`, `bank/client/ClientMain.java`
- **Work**: Small programs for a demonstration: start a server on a given port and database file with a few accounts, and a client that submits a debit and prints the result.
- **Requirements**: supports the README (no FR)
- **Done when**: The commands in the README start the server in one window and perform a debit from another, showing SUCCESS and then the balance.

### T021: Requirement coverage check
- **Depends on**: T019
- **Files**: `tests/bank/CoverageTest.java`
- **Work**: A test that reads the list FR-001 to FR-025 and checks that each ID appears in at least one test source file.
- **Requirements**: SC-006
- **Done when**: The test passes, and fails if an ID is removed from every test.

### T022: README
- **Depends on**: T020, T021
- **Files**: `README.md`
- **Work**: The four sections the assignment requires: (1) chosen option, (2) language and the coding agent used, stated honestly including that spec and plan were drafted with a chat assistant after the Gemini agent became unavailable, (3) setup, run, and test commands that work from a fresh clone, (4) an Engineering Reflection with concrete design flaws found before coding and corrections made, using the notes kept during the spec and plan phases. Add a short map of where each SDD document lives.
- **Requirements**: assignment deliverables
- **Done when**: Every command in the README has been run exactly as written in a fresh clone.

### T023: Final verification and submission
- **Depends on**: T022
- **Files**: none
- **Work**: Clone the repository into a new folder, run the README commands, run `mvn test` three times, check the history reads as phases (scaffold, constitution, spec, plan, tasks, one commit per task), confirm no secrets or database files are tracked, then push every branch to the remote.
- **Requirements**: assignment deliverables and rubric
- **Done when**: The fresh clone passes all tests, the remote shows all commits in order, and you can explain each design decision without notes.

## Requirement Coverage

| FR | Tasks |
|---|---|
| FR-001 | T003, T004, T005 |
| FR-002 | T005 |
| FR-003 | T012 |
| FR-004 | T012 |
| FR-005 | T012, T014 |
| FR-006 | T013, T014 |
| FR-007 | T012, T014, T015 |
| FR-008 | T013, T014 |
| FR-009 | T004, T010, T013, T014 |
| FR-010 | T007 |
| FR-011 | T007 |
| FR-012 | T006, T018 |
| FR-013 | T006, T010 |
| FR-014 | T006 |
| FR-015 | T002, T008, T018 |
| FR-016 | T008, T010 |
| FR-017 | T004, T006, T010 |
| FR-018 | T014, T015 |
| FR-019 | T002, T011 |
| FR-020 | T009, T010, T016, T017, T019 |
| FR-021 | T006, T007 |
| FR-022 | T003, T014 |
| FR-023 | T014 |
| FR-024 | T009, T016 |
| FR-025 | T012, T015 |

## Order of Execution

T001, T003, T002, T004, T005, T006, T007, T008, T009, T010, T011, T012, T013, T014, T015, T016, T017, T018, T019, T020, T021, T022, T023.