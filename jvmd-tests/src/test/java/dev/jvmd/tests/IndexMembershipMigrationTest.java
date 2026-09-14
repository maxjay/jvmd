package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: existing schema-1 artifacts acquire SCIP memberships without a rebuild. */
@Tag("phase-6")
class IndexMembershipMigrationTest {
    @TempDir Path root;
    @Test void migratesPopulatedDatabaseAndKeepsForeignKeysSound()throws Exception{
        Path file=root.resolve("old.db");Class.forName("org.sqlite.JDBC");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var q=c.createStatement();var input=IndexDatabase.class.getResourceAsStream("schema-1.sql")){
            for(String sql:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\\R"))if(!sql.isBlank())q.execute(sql);
            q.execute("INSERT INTO artifacts(id,gav,kind,sha256,path) VALUES(1,'fixture:a:1','jar','abc','/a.jar')");
            q.execute("INSERT INTO symbols(id,scip,artifact_id,kind,name,binary_key,name_path) VALUES(1,'maven fixture/a 1 A#',1,'class','A','A','A')");
        }
        try(var db=new IndexDatabase(file)){
            assertThat(db.<Long>read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM artifact_symbols")){r.next();return r.getLong(1);}})).isEqualTo(1L);
            assertThat(db.<Boolean>read(c->{try(var q=c.createStatement();var r=q.executeQuery("PRAGMA foreign_key_check")){return r.next();}})).isFalse();
        }
    }
}
