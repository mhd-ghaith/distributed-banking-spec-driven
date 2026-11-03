package bank.server;

import bank.common.BankException;
import bank.common.DebitRequest;
import bank.common.InsufficientFundsException;
import bank.common.InvalidRequestException;
import bank.common.Outcome;
import bank.common.Sqlite;
import bank.common.StorageException;
import bank.common.StaleKeyException;
import bank.server.AccountStore.Account;
import java.util.Objects;

/**
 * The decision logic of the Banking Service (plan section 6.2, spec FR-021).
 *
 * <p>Each request is decided inside one transaction that starts with BEGIN IMMEDIATE, so two
 * requests for the same account cannot both read the same state (FR-015). The rules are applied
 * in this order, and the first that matches wins:
 * <ol>
 *   <li>The request is invalid, or the account is unknown: {@link InvalidRequestException}.</li>
 *   <li>The key equals the account's last executed key: DUPLICATE_ACKNOWLEDGED with the current
 *       balance. The amount and the retry indicator are not looked at.</li>
 *   <li>The key is lower than the last executed key: {@link StaleKeyException}.</li>
 *   <li>The amount exceeds the balance: {@link InsufficientFundsException}. The key is not
 *       consumed.</li>
 *   <li>Otherwise the debit is executed.</li>
 * </ol>
 * The duplicate check comes before the funds check on purpose: after a debit succeeds the
 * balance may be too low for its own retry, and that retry must still be acknowledged (D-3).
 *
 * <p>A rejection is an exception thrown inside the transaction, which rolls it back, so a
 * rejected request cannot change anything. This class keeps no state of its own.
 */
public final class DebitService {

    /** The outcome and balance of a request that was decided without a rejection. */
    public record Result(Outcome outcome, long balance) {
    }

    private final Sqlite database;
    private final AccountStore accounts;

    public DebitService(Sqlite database, AccountStore accounts) {
        this.database = Objects.requireNonNull(database, "database");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /**
     * Decides one debit request.
     *
     * @throws InvalidRequestException    malformed request or unknown account
     * @throws StaleKeyException          the key is lower than the last executed key
     * @throws InsufficientFundsException the amount exceeds the balance (new key only)
     * @throws bank.common.StorageException the database failed or stayed locked too long
     */
    public Result execute(DebitRequest request) throws BankException {
        Objects.requireNonNull(request, "request");
        request.requireValid();

        return database.inTransaction(connection -> {
            Account account = accounts.find(connection, request.accountId())
                    .orElseThrow(() -> new InvalidRequestException("unknown account"));

            // Rule 2: already executed. Returning ends the transaction with nothing changed.
            if (request.key() == account.lastKey()) {
                return new Result(Outcome.DUPLICATE_ACKNOWLEDGED, account.balance());
            }
            // Rule 3: older than the last executed key.
            if (request.key() < account.lastKey()) {
                throw new StaleKeyException(account.balance(), account.lastKey());
            }
            // Rule 4: a new key, but the account cannot cover it. The key is not consumed.
            if (request.amount() > account.balance()) {
                throw new InsufficientFundsException(account.balance(), request.amount());
            }
            // Rule 5: execute. One guarded UPDATE changes the balance and the key together, in
            // this same transaction (FR-010). Returning commits it, and any exception rolls it
            // back, so the two values can never be stored apart.
            int changed = accounts.applyDebit(
                    connection, request.accountId(), request.amount(), request.key());
            if (changed != 1) {
                throw new StorageException(
                        "the guarded update changed " + changed + " rows instead of 1", null);
            }
            Account updated = accounts.find(connection, request.accountId())
                    .orElseThrow(() -> new StorageException(
                            "the account disappeared during the debit", null));
            return new Result(Outcome.SUCCESS, updated.balance());
        });
    }
}