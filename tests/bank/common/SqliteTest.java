package bank.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTest {

    @TempDir
    Path directory;

    private Sqlite database() throws StorageException {
        return new Sqlite(directory.resolve("test.db"), Duration.ofMillis(1234));
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    @Test
    @DisplayName("FR-019: a committed write is visible from another connection")
    void committedWriteIsVisibleFromAnotherConnection() throws Exception {
        Sqlite db = database();
        db.inTransaction(c -> {
            run(c, "CREATE TABLE t (v INTEGER)");
            run(c, "INSERT INTO t VALUES (42)");
            return null;
        });
        try (Connection other = db.open()) {
            assertEquals(42, number(other, "SELECT v FROM t"));
        }
    }

    @Test
    @DisplayName("FR-010: a rolled-back write is not visible, the original error propagates, "
            + "and the next transaction can start at once")
    void rolledBackWriteIsNotVisible() throws Exception {
        Sqlite db = database();
        db.inTransaction(c -> {
            run(c, "CREATE TABLE t (v INTEGER)");
            return null;
        });

        InvalidRequestException rejection = new InvalidRequestException("rejected");
        InvalidRequestException thrown = assertThrows(InvalidRequestException.class,
                () -> db.inTransaction(c -> {
                    run(c, "INSERT INTO t VALUES (1)");
                    throw rejection;
                }));
        assertSame(rejection, thrown);

        try (Connection other = db.open()) {
            assertEquals(0, number(other, "SELECT COUNT(*) FROM t"));
        }

        db.inTransaction(c -> {
            run(c, "INSERT INTO t VALUES (2)");
            return null;
        });
        try (Connection other = db.open()) {
            assertEquals(1, number(other, "SELECT COUNT(*) FROM t"));
        }
    }

    @Test
    @DisplayName("FR-019: connections use WAL journal mode, full synchronous mode and the "
            + "configured busy timeout")
    void connectionsUseTheConfiguredSettings() throws Exception {
        Sqlite db = database();
        try (Connection c = db.open()) {
            assertEquals("wal", text(c, "PRAGMA journal_mode"));
            assertEquals(2, number(c, "PRAGMA synchronous"));
            assertEquals(1234, number(c, "PRAGMA busy_timeout"));
        }
    }

    @Test
    @DisplayName("FR-019: data survives opening the database again through a new object")
    void dataSurvivesReopening() throws Exception {
        Sqlite first = database();
        first.inTransaction(c -> {
            run(c, "CREATE TABLE t (v INTEGER)");
            run(c, "INSERT INTO t VALUES (7)");
            return null;
        });

        Sqlite second = database();
        long value = second.inTransaction(c -> number(c, "SELECT v FROM t"));
        assertEquals(7, value);
    }

    @Test
    @DisplayName("FR-015: a transaction started while the write lock is held waits for the "
            + "busy timeout and then fails with a storage error")
    void secondTransactionWaitsForTheWriteLock() throws Exception {
        Sqlite db = new Sqlite(directory.resolve("lock.db"), Duration.ofMillis(300));
        db.inTransaction(c -> {
            run(c, "CREATE TABLE t (v INTEGER)");
            return null;
        });

        try (Connection holder = db.open()) {
            run(holder, "BEGIN IMMEDIATE");

            long start = System.nanoTime();
            StorageException failure = assertThrows(StorageException.class,
                    () -> db.inTransaction(c -> null));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis >= 150, "waited only " + elapsedMillis + " ms");
            assertInstanceOf(SQLException.class, failure.getCause());
            run(holder, "ROLLBACK");
        }

        String again = db.inTransaction(c -> "free");
        assertEquals("free", again);
    }

    @Test
    @DisplayName("Principle 6: a failing SQL statement becomes a storage error with its cause")
    void failingSqlBecomesStorageException() throws Exception {
        Sqlite db = database();
        StorageException failure = assertThrows(StorageException.class,
                () -> db.inTransaction(c -> {
                    run(c, "INSERT INTO missing_table VALUES (1)");
                    return null;
                }));
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    @DisplayName("Principle 6: an unusable database path becomes a storage error")
    void unusablePathBecomesStorageException() throws Exception {
        Path notAFolder = directory.resolve("not-a-folder");
        Files.writeString(notAFolder, "this is a file");
        assertThrows(StorageException.class,
                () -> new Sqlite(notAFolder.resolve("db.sqlite"), Duration.ofMillis(100)));
    }

    @Test
    @DisplayName("A negative busy timeout is rejected")
    void negativeTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Sqlite(directory.resolve("x.db"), Duration.ofMillis(-1)));
    }
}