package bank.server;

import bank.common.BankException;
import bank.common.Sqlite;
import bank.common.StorageException;
import bank.common.TransactionWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL access to the server's accounts (plan section 6.1).
 *
 * <p>Table {@code accounts}: {@code id} (primary key), {@code balance} (whole minor units, never
 * negative) and {@code last_key} (the key of the last executed debit, starting at 0, never
 * negative). The database itself rejects negative values, so no bug in the code can store one.
 *
 * <p>Methods that take a {@link Connection} are meant to run inside a transaction that the
 * caller controls ({@link Sqlite#inTransaction}), so that reading an account, deciding, and
 * updating it happen under one lock. Methods without a connection run their own transaction and
 * are meant for test setup and inspection.
 *
 * <p>This class holds only the {@link Sqlite} object, which is immutable and thread-safe
 * (constitution principle 5). No account data is kept in memory.
 */
public final class AccountStore {

    /** The state of one account. */
    public record Account(String id, long balance, long lastKey) {
    }

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS accounts (
                id       TEXT    PRIMARY KEY NOT NULL,
                balance  INTEGER NOT NULL CHECK (balance >= 0),
                last_key INTEGER NOT NULL DEFAULT 0 CHECK (last_key >= 0)
            )""";

    private final Sqlite database;

    /** Creates the accounts table if it does not exist. Existing data is not touched. */
    public AccountStore(Sqlite database) throws StorageException {
        this.database = Objects.requireNonNull(database, "database");
        transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(SCHEMA);
            }
            return null;
        });
    }

    /**
     * Creates an account with the given balance and last key 0, in its own transaction.
     * Fails with {@link StorageException} if the account exists or the balance is negative.
     */
    public void createAccount(String id, long initialBalance) throws StorageException {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO accounts (id, balance, last_key) VALUES (?, ?, 0)")) {
                statement.setString(1, id);
                statement.setLong(2, initialBalance);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Reads an account in its own transaction. */
    public Optional<Account> read(String id) throws StorageException {
        return transaction(connection -> find(connection, id));
    }

    /** Reads an account on the caller's connection, inside the caller's transaction. */
    public Optional<Account> find(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance, last_key FROM accounts WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Account(id, result.getLong(1), result.getLong(2)));
            }
        }
    }

    /**
     * Subtracts {@code amount} from the balance and sets the last key to {@code key}, in one
     * statement, but only if {@code last_key < key} and {@code balance >= amount}. Because both
     * columns change in the same statement, a debit can never be recorded without its key or the
     * other way round (spec FR-010).
     *
     * <p>The caller has already decided that the debit is allowed. These conditions repeat that
     * decision inside the statement, as a second guard: if they do not hold, nothing changes and
     * the result is 0.
     *
     * @return the number of rows changed: 1 if the debit was applied, 0 otherwise
     * @throws IllegalArgumentException if the amount or key is zero or less (a negative amount
     *                                  would add money to the account)
     */
    public int applyDebit(Connection connection, String id, long amount, long key)
            throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (key <= 0) {
            throw new IllegalArgumentException("key must be greater than zero");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = balance - ?, last_key = ? "
                        + "WHERE id = ? AND last_key < ? AND balance >= ?")) {
            statement.setLong(1, amount);
            statement.setLong(2, key);
            statement.setString(3, id);
            statement.setLong(4, key);
            statement.setLong(5, amount);
            return statement.executeUpdate();
        }
    }

    /**
     * Runs work in a transaction for the methods above. Their work only fails with SQL errors,
     * which {@link Sqlite} reports as {@link StorageException}, so no other kind of
     * {@link BankException} can occur here.
     */
    private <T> T transaction(TransactionWork<T> work) throws StorageException {
        try {
            return database.inTransaction(work);
        } catch (StorageException e) {
            throw e;
        } catch (BankException e) {
            throw new IllegalStateException("unexpected error kind in the account store", e);
        }
    }
}