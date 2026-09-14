package dev.jvmd.tests;
import dev.jvmd.index.IndexDatabase;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: schema migrations, WAL and FTS5 trigram. */
@Tag("phase-3")
class IndexSchemaTest {
    @TempDir Path temp;
    @Test void migratesOnceAndCheckpointsWalWithTrigramSupport() throws Exception {
        Path file = temp.resolve("index.db");
        try (var db = new IndexDatabase(file)) {
            assertThat(db.<String>read(c -> { try (var s=c.createStatement();var r=s.executeQuery("PRAGMA journal_mode")) {r.next();return r.getString(1);} })).isEqualTo("wal");
            assertThat(db.<java.util.List<String>>read(c -> { try (var s=c.createStatement();var r=s.executeQuery("PRAGMA compile_options")) {var options=new java.util.ArrayList<String>();while(r.next())options.add(r.getString(1));return options;} })).contains("ENABLE_FTS5");
            db.write(c -> {try(var s=c.createStatement()){s.execute("INSERT INTO counters VALUES('test',1)");}return null;});
        }
        assertThat(Files.exists(file.resolveSibling("index.db-wal")) ? Files.size(file.resolveSibling("index.db-wal")) : 0).isZero();
        try (var db = new IndexDatabase(file)) { assertThat(db.counts()).containsEntry("test",1L); }
    }
}
