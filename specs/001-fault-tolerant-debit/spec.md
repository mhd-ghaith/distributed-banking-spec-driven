# Feature Specification: Fault-Tolerant Debit Processing

**Feature Directory**: `specs/001-fault-tolerant-debit`

**Created**: 2026-11-02

**Status**: Draft

**Input**: User description: "Feature: Fault-tolerant debit of a bank account between an Order Service (client) and a Banking Service (server) over an unreliable network. WHY: A debit is not naturally repeatable. If the connection drops or the server crashes around the moment of execution, the client cannot tell whether the money was taken. Blind retries could debit twice, and giving up could lose the transaction. The system must make the debit take effect exactly once from the account holder's point of view, however many retries or failures occur."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reliable Exactly-Once Account Debit (Priority: P1)

An Order Service needs to debit an Account Holder's bank account for a purchase over a network. The Order Service issues debit requests with positive sequence keys. If no acknowledgment is received or a timeout occurs, the Order Service resends the debit request using the exact same sequence key. The Banking Service executes the debit exactly once, records the sequence key, and returns the updated balance. Subsequent duplicate requests with the same sequence key are recognized, not debited again, and acknowledged with the current balance.

**Why this priority**: Exactly-once debit execution is the core business capability and value proposition. Without reliable deduplication and accurate balance tracking, financial losses or double-charging occur.

**Independent Test**: Can be tested independently on a single account by issuing a debit request, verifying balance reduction and key update, followed by an immediate identical retry, verifying zero additional balance reduction and duplicate acknowledgment.

**Acceptance Scenarios**:

1. **Given** an account exists with balance 10,000 minor units and last executed key 0, **When** the Order Service submits a debit request for 2,500 minor units with sequence key 1, **Then** the Banking Service reduces the balance to 7,500 minor units, sets the last executed key to 1, and returns a success outcome with resulting balance 7,500.
2. **Given** an account has executed sequence key 1 with resulting balance 7,500 minor units, **When** the Order Service resubmits a debit request for 2,500 minor units with sequence key 1 and retry indicator set to true, **Then** the Banking Service does not change the balance and returns a duplicate-acknowledged outcome with balance 7,500.
3. **Given** an account has last executed key 1 and balance 7,500 minor units, **When** a debit request arrives with a gapped sequence key of 4 for 1,000 minor units, **Then** the Banking Service accepts the gapped key, reduces the balance to 6,500 minor units, sets the last executed key to 4, and returns a success outcome with balance 6,500.

---

### User Story 2 - Resilient Debit Recovery under Network and Server Failures (Priority: P2)

During transaction execution across an unreliable network, messages may be dropped, server execution may crash, or response delivery may be delayed past client timeout thresholds. The Order Service retries unacknowledged requests with backoff. When the server crashes or responses are lost after debit execution, subsequent retries allow the client and server to reconcile safely, leaving the account balance strictly correct.

**Why this priority**: Unreliable networks and server restarts are common real-world failure modes. Automated, guaranteed recovery without manual operator intervention is essential for production operations.

**Independent Test**: Can be tested independently using deterministic simulated failure scenarios (dropped request, pre-execution crash, post-execution response drop, delayed response past timeout) and asserting that the account holder's ending balance exactly matches starting balance minus total executed debits across all retries.

**Acceptance Scenarios**:

1. **Given** a debit request is dropped by network failure before reaching the Banking Service, **When** the Order Service's attempt timeout expires and it retries the request with the same sequence key, **Then** the Banking Service processes the request upon arrival, updates the balance once, and returns a success outcome.
2. **Given** the Banking Service crashes before executing a debit request, **When** the Banking Service restarts and the Order Service resends the debit request with the same sequence key, **Then** the Banking Service processes the debit exactly once and returns a success outcome.
3. **Given** the Banking Service executes and persists a debit of 1,000 minor units but the response message is lost in transit, **When** the Order Service times out and resends the debit request with the same sequence key, **Then** the Banking Service identifies the sequence key as already executed, makes no additional balance deduction, and returns duplicate-acknowledged with the correct balance.
4. **Given** the Banking Service executes a debit but the response arrives only after the Order Service attempt timeout has expired, **When** the Order Service issues a retry with the same sequence key, **Then** the Banking Service returns a duplicate-acknowledged outcome and the Order Service receives the definitive acknowledgment.

---

### User Story 3 - Strict Request Validation and Account Protection (Priority: P3)

When debit requests contain invalid amounts, reference non-existent accounts, exceed the available account balance, or present stale sequence keys, the Banking Service rejects the request immediately without debiting funds and without advancing the account's last executed sequence key.

**Why this priority**: Prevents unauthorized overdrafts, invalid financial states, and corruption of the account's sequence progression.

**Independent Test**: Can be tested independently by submitting debit requests with zero/negative amounts, amounts greater than balance, non-existent account IDs, and keys lower than the last executed key, confirming that the account balance and sequence key remain completely unchanged.

**Acceptance Scenarios**:

1. **Given** an account has a balance of 3,000 minor units and last executed key 2, **When** a debit request arrives for 5,000 minor units with sequence key 3, **Then** the Banking Service rejects the request with an insufficient funds outcome, balance remains 3,000, and last executed key remains 2.
2. **Given** an account has a balance of 5,000 minor units, **When** a debit request arrives with an amount of 0 or a negative value, **Then** the Banking Service rejects the request with an invalid request outcome without altering the balance or sequence key.
3. **Given** a debit request specifies an account identifier that does not exist, **When** the Banking Service evaluates the request, **Then** the Banking Service rejects the request with an invalid request outcome without creating or altering any account state.
4. **Given** an account has last executed key 5, **When** a debit request arrives with sequence key 3, **Then** the Banking Service rejects the request with a stale key outcome and does not alter the balance.

---

### User Story 4 - Concurrency and In-Flight Contention Management (Priority: P4)

When multiple requests or retries for the same account arrive simultaneously, the Banking Service serializes processing on that account so that operations execute deterministically without race conditions. A retry arriving while the original request is actively processing waits and receives the final acknowledgment. Concurrent requests for different accounts proceed without mutual blocking.

**Why this priority**: High-concurrency environments and rapid retry loops can cause race conditions if concurrent execution is uncoordinated. Serialization per account guarantees data consistency.

**Independent Test**: Can be tested independently by dispatching overlapping concurrent requests and retries to a single account as well as concurrent requests across different accounts, verifying strict serial correctness per account and non-blocking execution across separate accounts.

**Acceptance Scenarios**:

1. **Given** an initial debit request is actively in progress on an account, **When** a duplicate retry with the same sequence key arrives before the first finishes, **Then** the retry waits until the active operation completes and receives the acknowledgment with the resulting balance.
2. **Given** two concurrent debit requests with different sequence keys arrive for the same account, **When** processed by the Banking Service, **Then** they are executed one at a time, each evaluated against the state left by the preceding one under the key rules (a lower key processed after a higher one receives a stale key outcome). A conforming Order Service never sends this combination, because it has at most one unresolved transaction per account.
3. **Given** concurrent debit requests arrive targeting two distinct accounts, **When** processed by the Banking Service, **Then** processing of one account does not block or delay the other account.

---

### User Story 5 - Client Sequence Continuity and Retry Exhaustion Handling (Priority: P5)

The Order Service manages transaction lifecycle states reliably. When all configured retries are exhausted without a definitive response from the Banking Service, the Order Service reports "outcome unknown" (never "failed"), retains the transaction as pending, and refuses to issue a higher sequence key for that account until the pending transaction reaches a definitive outcome. Across client restarts, the sequence counter never goes backwards.

**Why this priority**: Prevents client-side state corruption, sequence regression, or false cancellation assumptions when network outages are prolonged.

**Independent Test**: Can be tested independently by simulating a persistent network disconnection, verifying that retries terminate with "outcome unknown", confirming that new higher-keyed debits for that account are blocked, restarting the client, and asserting the sequence counter continues without regression upon reconnect.

**Acceptance Scenarios**:

1. **Given** a debit request cannot reach the Banking Service across all configured retry attempts, **When** retries are exhausted, **Then** the Order Service reports an outcome of outcome unknown with the associated sequence key, keeps the transaction pending, and does not issue any higher sequence key for that account.
2. **Given** an Order Service has an unresolved transaction with sequence key 4 on an account, **When** the Order Service restarts, **Then** it resumes that transaction with key 4 and the same amount, and any later new transaction for that account uses a key higher than 4.
3. **Given** an account has a pending transaction that reported outcome unknown, **When** a subsequent retry of that same sequence key receives a success or duplicate-acknowledged outcome, **Then** the Order Service marks the transaction resolved and permits issuing subsequent higher sequence keys.

---

### Edge Cases

- **Duplicate Sequence Key**: When a request arrives with a key equal to the account's last executed key, the server must not debit the account again and must return duplicate-acknowledged with the current balance.
- **Retry of an Executed Debit After the Balance Has Dropped**: When a retry carries a key already executed, the server returns duplicate-acknowledged even if the retry's amount now exceeds the account's remaining balance (FR-021).
- **Stale Sequence Key**: When a request arrives with a key strictly less than the account's last executed key, the server must reject it with a stale key outcome and leave the balance unchanged.
- **Gapped Sequence Key**: When a request arrives with a key strictly greater than the last executed key by more than 1 (e.g., key 7 when last executed key was 2), the server must accept and execute the debit, advancing the last executed key directly to 7 without backfilling skipped numbers.
- **Concurrent Requests on the Same Account**: Simultaneous requests for the same account must be serialized; race conditions must never produce negative balances or double debits.
- **Concurrent Requests on Different Accounts**: Operations targeting distinct accounts must execute independently without contention or cross-account locking.
- **Retry Arriving During Processing**: A retry arriving while the original request is actively executing must wait for the active request to complete and receive the acknowledgment.
- **Insufficient Funds**: When requested debit amount exceeds account balance, the server rejects the request without changing the balance and without consuming or recording the request key.
- **Invalid Debit Amount**: Non-positive amounts (zero or negative) are rejected as invalid requests without balance or key changes.
- **Unknown Account**: Requests referencing non-existent account identifiers are rejected as invalid requests without creating records or side effects.
- **Exhaustion of Client Retries**: When all client retry attempts time out or fail, the client reports outcome unknown (never failed), preserves the transaction as pending, and prohibits issuing higher keys for that account until resolved.
- **Server Restart Between Attempts**: The server preserves account balances and last executed keys across restarts; subsequent retries after a restart must be deduplicated accurately.
- **Client Restart Between Attempts**: The client preserves account sequence tracking across restarts; sequence keys must never regress or reuse past keys.
- **Informational Retry Indicator**: A request with the retry indicator set to false whose key matches an already executed key must still be treated as a duplicate; correctness depends solely on the sequence key.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST represent all monetary amounts and balances as whole numbers in minor currency units (e.g., cents), with no floating-point representation. A valid debit amount is greater than zero.
- **FR-002**: Banking Service MUST maintain for each account a non-negative balance in minor units and the account's last executed sequence key, initialized to 0 upon account creation.
- **FR-003**: Order Service MUST maintain a separate, monotonically increasing sequence of positive integer keys for each account.
- **FR-004**: Order Service MUST NOT allow sequence keys for an account to regress or reset to a lower value, including across client restarts.
- **FR-005**: Order Service MUST restrict in-flight activity to at most one unresolved transaction per account at any given time.
- **FR-006**: Order Service MUST reuse the exact same sequence key on every retry of an unresolved transaction until a definitive outcome is received.
- **FR-007**: Order Service MUST issue a higher sequence key for an account only after the current transaction for that account achieves a definitive outcome (success, duplicate-acknowledged, insufficient funds, invalid request, or stale key).
- **FR-008**: Order Service MUST implement at-least-once delivery by resending unacknowledged debit requests with configurable backoff when an attempt times out or experiences a communication failure.
- **FR-009**: Order Service MUST format each debit request to include: account identifier, debit amount in integer minor units, sequence key, and an informational retry indicator set to true on retries and false on initial attempts.
- **FR-010**: Banking Service MUST execute the deduction of the debit amount from the account balance and the recording of the sequence key as a single indivisible, atomic step whenever the request key is strictly greater than the account's last executed key.
- **FR-011**: Banking Service MUST accept gapped sequence keys (keys greater than the last executed key by more than 1), executing the debit and updating the last executed key to the request key.
- **FR-012**: Banking Service MUST recognize a debit request whose sequence key equals the account's last executed key as a duplicate, MUST NOT debit the balance again, and MUST return a duplicate-acknowledged outcome with the account's current balance.
- **FR-013**: Banking Service MUST determine transaction idempotency and duplicate status solely from the sequence key, treating the retry indicator as purely diagnostic metadata.
- **FR-014**: Banking Service MUST reject any request with a sequence key strictly less than the account's last executed key with a stale key outcome, with no modification to the account balance.
- **FR-015**: Banking Service MUST handle requests for the same account one at a time, ensuring that any retry arriving while a request is in progress waits and receives the acknowledgment upon completion.
- **FR-016**: Banking Service MUST permit concurrent requests for different accounts to proceed independently without blocking.
- **FR-017**: Banking Service MUST reject a request with an unknown account identifier, a missing field, a non-positive sequence key, or a non-positive amount with an invalid request outcome, without modifying the balance or recording the sequence key.
- **FR-018**: Order Service MUST report a transaction outcome as "outcome unknown" (and MUST NOT report it as "failed") when all configured retries are exhausted without a definitive response, retaining the transaction as pending and blocking newer keys for that account.
- **FR-019**: Banking Service and Order Service MUST preserve account state (balances and last executed keys) and client sequence state across restarts.
- **FR-020**: System MUST provide a configurable, deterministic fault-injection mechanism driven by reproducible seeds capable of simulating: request drops before server receipt, server crashes before execution, response drops after execution, and response delays exceeding client timeout.
- **FR-021**: Banking Service MUST evaluate each request in this order and stop at the first rule that applies: (1) invalid request per FR-017; (2) key equal to the account's last executed key: duplicate-acknowledged with the current balance, regardless of the request's amount and the account's current balance; (3) key lower than the last executed key: stale key; (4) key greater than the last executed key and amount exceeding the balance: insufficient funds, with no state change and the key not recorded; (5) otherwise: execute the debit atomically per FR-010. The insufficient-funds check MUST apply only to keys greater than the last executed key.
- **FR-022**: Order Service MUST treat success, duplicate-acknowledged, insufficient funds, invalid request, and stale key as definitive outcomes, and MUST treat an error outcome, a timeout, and a missing response as non-definitive (the same key is retried). Order Service MUST report to its caller: completed (success or duplicate-acknowledged), rejected with the reason (insufficient funds, invalid request, stale key), or outcome unknown (FR-018).
- **FR-023**: Order Service MUST match responses to transactions by account and sequence key, and MUST ignore, without changing any state, a response for a transaction that has already reached a definitive outcome.
- **FR-024**: Fault injection MUST be disabled by default and, when disabled, MUST NOT alter the behavior of the client or server.
- **FR-025**: After a client restart, an unresolved transaction MUST be resumed with the same key and amount, and every new transaction MUST use a key higher than any key previously issued for that account.

### Key Entities *(include if feature involves data)*

- **Account**: Represents an account holder's balance and transaction progress. Key attributes: account identifier, balance (non-negative integer minor units), last executed sequence key (non-negative integer).
- **Debit Request**: Represents a client instruction to withdraw funds. Key attributes: account identifier, amount (positive integer minor units), sequence key (positive integer), retry indicator (boolean).
- **Debit Response**: Represents the server's reply to a debit request. Key attributes: outcome (one of: success, duplicate-acknowledged, insufficient funds, invalid request, stale key, error), resulting balance (integer minor units).
- **Client Transaction State**: Represents the client's internal tracking for an account's active debit. Key attributes: account identifier, sequence key, amount, attempt count, status (in-flight, pending outcome unknown, resolved).
- **Failure Simulation Rule**: Represents configuration used by test operators to inject reproducible faults. Key attributes: failure type (request drop, pre-execution crash, post-execution response drop, response delay), delay duration (if applicable), deterministic seed / trigger criteria.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of tested failure scenarios (including request drops, pre-execution crashes, response drops, and response timeouts) and retry combinations, the final account balance equals the starting balance minus the sum of distinct executed debits, with 0 lost transactions and 0 duplicate debits.
- **SC-002**: 100% of duplicate debit requests with previously executed sequence keys return duplicate-acknowledged outcomes with 0 additional balance deductions.
- **SC-003**: 100% of invalid, overdrawing, or stale requests are rejected with 0 balance changes and 0 changes to the account's last executed sequence key.
- **SC-004**: When client retries are exhausted under simulated permanent outages, 100% of reported states indicate "outcome unknown" (0% reported as "failed").
- **SC-005**: 100% of fault injection scenarios configured with identical seed parameters produce identical, reproducible failure paths and final states across automated test runs.
- **SC-006**: Every requirement FR-001 through FR-025 is verified by at least one automated test that names it.

## Assumptions

- **Single Client Sequence Authority**: For any given account, sequence keys are generated by a single logical client sequence coordinator, preventing concurrent competing sequence generators for that account.
- **Minimal Durable Server Memory**: The Banking Service requires only the account's balance and last executed sequence key as durable state to guarantee idempotency; storing full historical request logs is not required for correctness.
- **Standard Minor Currency Units**: All balances and transactions are denominated in an integer minor unit (e.g., cents), eliminating floating-point rounding errors.
- **Network Unreliability**: The communication medium between Order Service and Banking Service is assumed to be unreliable, subject to arbitrary message drops, latency, and duplicates.
- **Durable Persistence Across Restarts**: The server and client storage systems survive process restarts, retaining all committed account balances, last executed keys, and client sequence counters.
- **Account Setup**: Accounts and their starting balances are created by test setup, not by a debit request.
- **Informational Diagnostics**: The retry indicator is recorded for operational diagnostics and auditing, but correctness logic strictly relies on sequence keys.

## Out of Scope

- Deposits, transfers between accounts, and account creation requests.
- Authentication and authorization.
- Multiple currencies and fees.
- Multiple server replicas and distributed consensus.
- Any user interface.