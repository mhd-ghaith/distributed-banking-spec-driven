Feature: Fault-tolerant debit of a bank account between an Order Service (client) and a Banking Service (server) over an unreliable network.



WHY: A debit is not naturally repeatable. If the connection drops or the server crashes around the moment of execution, the client cannot tell whether the money was taken. Blind retries could debit twice, and giving up could lose the transaction. The system must make the debit take effect exactly once from the account holder's point of view, however many retries or failures occur.



ACTORS: Order Service (client), Banking Service (server), Account Holder (whose balance changes), Test Operator (configures simulated failures).



MESSAGES:

\- Debit request: account identifier, amount (integer minor units), sequence key, retry indicator (true on retries).

\- Debit response: outcome (success, duplicate-acknowledged, insufficient funds, invalid request, stale key, error) and the resulting balance.



BEHAVIOR:

\- The client resends a request with backoff when it gets no response or hits a per-attempt timeout (at-least-once delivery).

\- The client keeps one sequence of keys per account. Keys are positive integers, and the sequence never goes backwards, including after a client restart. The client has at most one unresolved transaction per account at a time: it reuses the same key on every retry of that transaction and issues a higher key only after the transaction has a definitive outcome (success, duplicate-acknowledged, insufficient funds, or invalid request).

\- Each account starts with a last executed key of 0. The server compares the request key to the account's last executed key. A greater key (even one that skips values) means: debit and record the key together as one indivisible step. An equal key means: do not debit, return the success acknowledgment with the account's current balance. A lower key means: reject as stale, no change.

\- The retry indicator is informational only. Correctness depends solely on the key, so a request whose key was already executed is a duplicate regardless of the indicator. The server records the indicator for diagnostics.

\- Requests for the same account are handled one at a time. A retry arriving while the original is still being processed waits and then receives the acknowledgment.

\- Insufficient funds, an amount of zero or less, and an unknown account are rejected without changing the balance or consuming the key.

\- When retries run out, the client reports "outcome unknown" (never "failed") including the key. It keeps that transaction pending and issues no higher key for that account until a later retry of the same key gets a definitive outcome.

\- The system includes a configurable, reproducible way to simulate failures.



FAILURE SCENARIOS (each must end with the exact correct balance):

1\. The request never reaches the server.

2\. The server crashes before executing the debit.

3\. The server executes and saves the debit, then the response is lost on the way back.

4\. The server responds only after the client's timeout has expired.



EDGE CASES TO COVER: duplicate key, stale key, gapped key (accepted), concurrent requests on one account, concurrent requests on different accounts, retry during processing, insufficient funds, invalid amount, unknown account, retries exhausted, server restart between attempts, client restart with the key sequence continuing.



SUCCESS CRITERIA: In every scenario and any combination of retries, the final balance equals the starting balance minus the sum of the distinct successfully executed debits. No transaction is lost and none is applied twice.



ASSUMPTIONS: Keys for one account come from a single client sequence. The server's only durable memory of past requests is each account's balance and last executed key.



Do not describe code, classes, database tables, or libraries in the spec.

