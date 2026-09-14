package dev.jvmd.index;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Implements 4.4: one migrated WAL database, concurrent readers and a serialized writer. */
public final class IndexDatabase implements AutoCloseable {
    /** Implements 4.4: checked JDBC operations with scoped connection ownership. */
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws Exception; }
    private final Path path;
    private final Connection writer;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("jvmd-index-writer").factory());
    private final ArrayBlockingQueue<Connection> readers = new ArrayBlockingQueue<>(4);
    private volatile boolean closed;
    public IndexDatabase(Path path) throws Exception {
        this.path = path.toAbsolutePath(); Files.createDirectories(this.path.getParent());
        Class.forName("org.sqlite.JDBC");
        writer = connect(false);
        try (var statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA synchronous=NORMAL");
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) { result.next(); version = result.getInt(1); }
            if (version > 2) throw new IllegalStateException("Index schema is newer than this daemon: " + version);
            for (int migration = version + 1; migration <= 2; migration++) {
                writer.setAutoCommit(false);
                try (var stream = IndexDatabase.class.getResourceAsStream("schema-" + migration + ".sql")) {
                    if (stream == null) throw new IllegalStateException("Missing index migration");
                    for (String sql : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R"))
                        if (!sql.isBlank()) statement.execute(sql);
                    writer.commit();
                } catch (Exception e) { writer.rollback(); throw e; }
                finally { writer.setAutoCommit(true); }
            }
        }
        for (int i = 0; i < 4; i++) readers.add(connect(true));
    }
    private Connection connect(boolean readOnly) throws SQLException {
        var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000"); s.execute("PRAGMA foreign_keys=ON");
            long size; try { size = Files.exists(path) ? Files.size(path) : 0; } catch (java.io.IOException e) { size = 0; }
            s.execute("PRAGMA mmap_size=" + size);
            if (readOnly) s.execute("PRAGMA query_only=ON");
        }
        return c;
    }
    public <T> T read(Work<T> work) throws Exception {
        if (closed) throw new IllegalStateException("Index closed");
        Connection connection = readers.take();
        try { return work.run(connection); } finally { readers.add(connection); }
    }
    public <T> T write(Work<T> work) throws Exception {
        if (closed) throw new IllegalStateException("Index closed");
        try { return writes.submit(() -> {
            writer.setAutoCommit(false);
            try { T value = work.run(writer); writer.commit(); return value; }
            catch (Exception | Error error) { writer.rollback(); throw error; }
            finally { writer.setAutoCommit(true); }
        }).get(); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception x) throw x; if (e.getCause() instanceof Error x) throw x; throw e; }
    }
    public Map<String, Long> counts() throws Exception {
        return read(c -> {
            var values = new LinkedHashMap<String, Long>();
            for (String table : List.of("artifacts", "symbols", "edges", "simple_names"))
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); values.put(table, r.getLong(1)); }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT name,value FROM counters")) { while (r.next()) values.put(r.getString(1),r.getLong(2)); }
            return values;
        });
    }
    public void checkpoint() throws Exception {
        writes.submit(() -> { try (var s = writer.createStatement()) { s.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } return null; }).get();
    }
    @Override public void close() throws Exception {
        if (closed) return; closed = true;
        writes.shutdown();
        if (!writes.awaitTermination(30, TimeUnit.SECONDS)) writes.shutdownNow();
        var allReaders = new ArrayList<Connection>();
        for (int i = 0; i < 4; i++) allReaders.add(readers.take());
        for (var c : allReaders) c.close();
        try (var s = writer.createStatement()) { s.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        finally { writer.close(); }
    }
}
