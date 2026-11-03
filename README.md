# Fault-Tolerant Distributed Banking Transaction (Spec-Driven Development)

Course assignment: Distributed Systems Fundamentals, practical. Option 1, **Fault-Tolerant Distributed Banking Transaction**, built with Spec-Driven Development (GitHub Spec Kit workflow).

## 1. Language, tools, and how the work was produced

- **Language:** Java. The code targets **Java 21** (`maven.compiler.release` is 21, so no newer language features or APIs are used) and is built here with **OpenJDK 27**.
- **Build and test:** Maven 3.10.0, JUnit Jupiter 5.13.4.
- **Database:** SQLite through the `org.xerial:sqlite-jdbc` driver, version 3.51.0.0. No other runtime dependency.
- **IDE and OS:** IntelliJ IDEA on Windows 11.
- **Workflow framework:** GitHub Spec Kit, initialized with the Docker Agent integration.

### Coding agent

**Docker Agent with a Gemini model** (`google/gemini-3.5-flash`, later `gemini-3.8-flash`). It generated the Spec Kit scaffold's constitution text (`/speckit-constitution`) and the first draft of the specification (`/speckit-specify`, from my input description). During development (stopped at T009), the Gemini API returned **HTTP 429 (too many requests) and HTTP 503 (service unavailable)** errors, and the agent could not be used reliably after that. Its token usage at that point was small, so this was rate limiting and service overload, not an exhausted allowance.

## 2. Spec-Driven Development: where everything lives

| Phase | Document | Created in commit |
|---|---|---|
| 0. Scaffold | `.specify/`, `.agents/` (Spec Kit templates and agent skills) | "Initialize Spec Kit scaffold with Docker Agent integration" |
| 1. Constitution | `.specify/memory/constitution.md` (v1.0.0, 11 principles) | "Add project constitution v1.0.0" |
| 2. Specification | `specs/001-fault-tolerant-debit/spec.md` (FR-001 to FR-025, user stories, edge cases, success criteria) and `specs/001-fault-tolerant-debit/checklists/requirements.md` | "Add feature specification (FR-001..FR-025), generated from notes/spec-input.md and refined after review" |
| 3. Plan | `specs/001-fault-tolerant-debit/plan.md` | "Add implementation plan ..." |
| 4. Tasks | `specs/001-fault-tolerant-debit/tasks.md` (T001 to T023, with a requirement coverage table) | "Add task breakdown (T001-T023) ..." |
| 5. Implementation | One commit per task, message starting with the task id and citing requirement ids | `T001: ...` through `T009: ...` |

`notes/spec-input.md` is the description given to the specification step.

Documents were changed **before** the code whenever implementation work exposed a gap. Those commits are separate and named for the change (for example "Fix task order and shared types: T003 before T002 ..."). View the whole history with `git log --oneline`.

## 3. Build and test

Prerequisites: JDK 21 or newer, Maven 3.9 or newer, Git. No SQLite installation is needed (the driver bundles it).

```powershell
git clone https://github.com/<your-username>/distributed-banking-spec-driven.git
cd distributed-banking-spec-driven
mvn test
```

All tests are run by that one command. The expected end of the output is `Tests run: 206, Failures: 0, Errors: 0, Skipped: 0` followed by `BUILD SUCCESS`. The run takes under a minute, most of it in the concurrency tests.

Notes:
- Tests use temporary folders, so they leave no database files behind.
- `pom.xml` passes `--enable-native-access=ALL-UNNAMED` to the test JVM, which silences warnings that newer JDKs print when the SQLite native library loads.
- To run one test class: `mvn test -Dtest=DebitServiceConcurrencyTest`.
- The source folder is `src/main/java`. The test folder is the top-level `tests/` folder (Maven's `testSourceDirectory` is set to `tests`), as the assignment layout requires.

## 4. Repository layout

| Path | Contents |
|---|---|
| `src/main/java/bank/common` | `Outcome`, `DebitRequest`, `DebitResponse`, `WireFormat`, the error types, `Sqlite`, `TransactionWork` |
| `src/main/java/bank/server` | `AccountStore`, `DebitService` |
| `src/main/java/bank/fault` | `FaultPoint`, `FaultAction`, `FaultRule`, `FaultConfig`, `FaultInjector` |
| `tests/bank/...` | Tests mirroring the packages above |
| `specs/001-fault-tolerant-debit` | Specification, plan, tasks, checklist |
| `.specify`, `.agents` | Spec Kit scaffold, constitution, agent skills |
| `notes` | Input description for the specification step |
| `coding-agent.yaml` | Docker Agent configuration (model, skills enabled, filesystem and shell tools). Contains no API key |

## 5. Design summary

**Problem.** A debit is not repeatable by nature. If the network drops or the server crashes around the moment of execution, the client cannot tell whether the money was taken. The design combines at-least-once delivery (client retries) with an idempotency key (the server recognizes a request it has already executed), giving an effectively exactly-once debit.

**Protocol.** A request carries an account id, an amount in integer minor units, a sequence key, and an informational retry flag. A response carries one of six outcomes (SUCCESS, DUPLICATE_ACKNOWLEDGED, INSUFFICIENT_FUNDS, INVALID_REQUEST, STALE_KEY, ERROR), the account and key echoed back, an optional balance, and an optional reason. The wire format is plain `name=value` lines with no JSON library.

**Server decision rules** (spec FR-021), applied in one transaction that starts with `BEGIN IMMEDIATE`, stopping at the first that matches:

1. Invalid request or unknown account: INVALID_REQUEST.
2. Key equals the account's last executed key: DUPLICATE_ACKNOWLEDGED with the current balance (the amount and the retry flag are ignored).
3. Key lower than the last executed key: STALE_KEY.
4. Amount above the balance: INSUFFICIENT_FUNDS (the key is **not** consumed).
5. Otherwise one `UPDATE` subtracts the amount and records the key together, then commit: SUCCESS.

**Key design decisions** (the plan numbers these D-1 to D-9):
- **No in-process locks.** Correctness comes from SQLite transactions only. `BEGIN IMMEDIATE` takes the write lock before the account is read, so two requests for the same account cannot both read the same state. The second waits (busy timeout) and then sees the first one's result.
- **Duplicate check before the funds check.** After a debit of 800 from 1000, the balance is 200, and a retry of that same 800 would fail a funds check. Answering "insufficient funds" for money that was taken is the failure this system exists to prevent.
- **Atomic balance and key.** A single `UPDATE` changes both columns, guarded again by `last_key < key AND balance >= amount`. Table `CHECK` constraints reject negative balances and keys. A failure in the update leaves both unchanged (tested with database triggers).
- **Deterministic fault injection.** Each decision is a pure function of the seed, fault point, account, key, occurrence number and rule position, so results do not depend on thread timing and a failing run can be replayed.
- **Faults happen outside transactions** (design), so an injected delay never holds the database write lock.
- **Stale key rejected, gapped keys accepted.** Keys must increase but need not be consecutive, because rejections do not consume keys.
- **"Outcome unknown" blocks higher keys.** After retries run out, the client keeps the transaction pending and issues no higher key for that account until a retry of the same key gets a definitive answer. Otherwise the old key could become stale and its outcome permanently unknowable.

## 6. Requirement coverage so far

| Requirement | Verified by | State |
|---|---|---|
| FR-001, FR-002 (integer minor units, balance and key storage) | `DebitRequestTest`, `WireFormatTest`, `AccountStoreTest` | Verified |
| FR-009 (request fields), FR-017 (invalid requests), FR-022 (outcome classification) | `DebitRequestTest`, `WireFormatTest`, `OutcomeTest`, `DebitServiceTest` | Verified at message and service level |
| FR-010, FR-011 (atomic debit, gapped keys) | `AccountStoreTest`, `DebitServiceExecutionTest` | Verified |
| FR-012, FR-013, FR-014, FR-021 (duplicate, indicator, stale, evaluation order) | `DebitServiceTest`, `DebitServiceExecutionTest` | Verified |
| FR-015, FR-016 (same-account serialization, independent accounts) | `DebitServiceConcurrencyTest` (20 repetitions each) | Verified at service level, not yet over HTTP |
| FR-019 (durability) | `SqliteTest`, `AccountStoreTest` | Settings and reopening verified, server restart test not done |
| FR-020, FR-024 (deterministic injection, off by default) | `FaultInjectorTest` | Injector verified, not yet wired into server or client |
| FR-003 to FR-008, FR-018, FR-023, FR-025 (client behavior) | none yet | **Not implemented** |

## 7. Engineering reflection

Problems that the SDD process surfaced before or during coding, each fixed in the documents first:

1. **A retry of an executed debit could be reported as "insufficient funds".** The first specification draft applied the funds check to every request. Tracing scenario S-3 (response lost after commit) with real numbers showed that a retry of an 800 debit on an account now holding 200 would be rejected, hiding a successful charge. Fixed by an explicit evaluation order (FR-021) with the duplicate check first, and covered by a dedicated test.
2. **"Outcome unknown" could never be resolved.** My first version let the client move on to a higher key after exhausting retries, which would make the old key stale and its fate permanently unknowable. Fixed by keeping the transaction pending and blocking higher keys.
3. **STALE_KEY was missing from the list of definitive outcomes** in the draft, leaving the client's behavior on it undefined.
4. **A restart scenario contradicted resuming a pending transaction.** The draft said the client must not reissue its current key after a restart. Rewritten so a pending transaction resumes with the same key and amount.
5. **The generated draft invented a performance target** (100 ms per transaction) that the assignment never asked for and that would make tests flaky. Removed.
6. **Task order bug.** The task list had the SQLite helper depending on an error type defined in a later task, and put one subclass of a sealed class in another package. Fixed in the documents (task T003 before T002) before any code was written.
7. **Unspecified transaction start.** The plan said transactions use `BEGIN IMMEDIATE` but not how. I chose explicit SQL statements over the driver's transaction-mode option so the locking does not depend on driver internals, and updated the plan and tasks first.
8. **Undecodable requests have nothing to echo.** The client matches responses by account and key, so a reply to an unreadable request could never be matched and would be retried forever. Fixed: the client validates before issuing a key, and an undecodable request gets an empty echo.
9. **Negative amounts credit the account.** While writing tests for the guarded update, I noticed `balance - (-100)` silently adds money. The store now refuses non-positive amounts and keys with an exception.
10. **"Occurrence" was undefined** in the fault injector task. Defined as one consultation per fault point, account and key before coding, since the scenario tests depend on it.

How the coding assistants were steered: each task was a separate commit with its own tests, documents were corrected before code whenever a gap was found, and the generated first draft of the specification was reviewed line by line against the five open design questions and the four failure scenarios.
