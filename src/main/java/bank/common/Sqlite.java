package bank.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

/**
 * Opens connections to one SQLite database file and runs transactions on it.
 *
 * <p>Settings applied by this class (spec FR-019, FR-015):
 * <ul>
 *   <li>WAL journal mode, set once when this object is created and stored in the file.</li>
 *   <li>Full synchronous mode on every connection, so a committed transaction is on disk.</li>
 *   <li>A busy timeout on every connection, so a request that finds the database locked
 *       waits for the lock instead of failing at once.</li>
 *   <li>Every transaction begins with an explicit BEGIN IMMEDIATE, which takes the write lock
 *       before any data is read. Two requests for the same account therefore cannot both read
 *       the same state: the second waits until the first has committed or rolled back.</li>
 * </ul>
 *
 * <p>The object holds only the database URL and the timeout, both immutable, so it can be
 * shared by many threads (constitution principle 5). Each call uses its own connection.
 */
public final class Sqlite {

    private final String url;
    private final int busyTimeoutMillis;

    /**
     * Prepares the database file, creating its folder if needed and switching it to WAL mode.
     *
     * @param databaseFile the SQLite database file (created on first use)
     * @param busyTimeout  how long a connection waits for a lock held by another connection
     */
    public Sqlite(Path databaseFile, Duration busyTimeout) throws StorageException {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative()) {
            throw new IllegalArgumentException("busy timeout must not be negative");
        }
        this.busyTimeoutMillis = Math.toIntExact(busyTimeout.toMillis());

        Path absolute = databaseFile.toAbsolutePath();
        try {
            Path folder = absolute.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
        } catch (IOException e) {
            throw new StorageException("cannot create the database folder: " + e.getMessage(), e);
        }
        this.url = "jdbc:sqlite:" + absolute.toString().replace('\\', '/');

        try (Connection connection = open()) {
            execute(connection, "PRAGMA journal_mode = WAL");
        } catch (SQLException e) {
            throw new StorageException(
                    "cannot switch the database to WAL mode: " + e.getMessage(), e);
        }
    }

    /**
     * Opens a new connection with the project's settings. The connection is in auto-commit
     * mode, so it starts no transaction by itself. The caller must close it.
     */
    public Connection open() throws StorageException {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(url);
            execute(connection, "PRAGMA synchronous = FULL");
            execute(connection, "PRAGMA busy_timeout = " + busyTimeoutMillis);
            return connection;
        } catch (SQLException e) {
            closeAfterFailure(connection, e);
            throw new StorageException("cannot open the database: " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code work} in one transaction on a fresh connection.
     *
     * <p>The transaction starts with BEGIN IMMEDIATE (waiting up to the busy timeout for the
     * write lock). If {@code work} returns, the transaction is committed and its result is
     * returned. If {@code work} throws, the transaction is rolled back and the same exception
     * reaches the caller. Database errors are reported as {@link StorageException}.
     */
    public <T> T inTransaction(TransactionWork<T> work) throws BankException {
        Objects.requireNonNull(work, "work");
        try (Connection connection = open()) {
            execute(connection, "BEGIN IMMEDIATE");
            try {
                T result = work.run(connection);
                execute(connection, "COMMIT");
                return result;
            } catch (BankException | SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("database operation failed: " + e.getMessage(), e);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Rolls back after a failure. A failure of the rollback itself is attached to the original. */
    private static void rollback(Connection connection, Exception original) {
        try {
            execute(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void closeAfterFailure(Connection connection, Exception original) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }
}