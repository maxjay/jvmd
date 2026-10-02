package dev.jvmd.tests;

import dev.jvmd.core.FileObservationJournal;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.core.Hashing;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §90–93: restart-safe reuse of file observations. */
class FileStatePersistenceTest {
    @TempDir Path root;

    private static long counter(FileStateRegistry registry,String name){return ((Number)registry.status().get(name)).longValue();}
    private FileStateRegistry restarted(Path journal,long window){
        var registry=new FileStateRegistry();registry.racyWindowNanos(window);registry.persistence(journal);return registry;
    }

    @Test void unchangedFilesReuseContentHashesAfterRestart()throws Exception{
        Path journal=root.resolve("state/observations.bin");
        var files=new ArrayList<Path>();
        for(int i=0;i<20;i++){Path file=root.resolve("src/F"+i+".java");Files.createDirectories(file.getParent());Files.writeString(file,"class F"+i+"{}");files.add(file);}

        var first=restarted(journal,0);
        var expected=new HashMap<Path,String>();
        for(Path file:files)expected.put(file,first.hash(file));
        first.flushObservations();
        assertThat(counter(first,"hashes")).isEqualTo(20);

        var second=restarted(journal,0);
        assertThat(counter(second,"restored_observations")).isEqualTo(20);
        for(Path file:files)assertThat(second.hash(file)).isEqualTo(expected.get(file)).isEqualTo(Hashing.sha256(file));
        assertThat(counter(second,"hashes")).isZero();
        assertThat(counter(second,"bytes_hashed")).isZero();
        assertThat(counter(second,"restart_hash_reuse")).isEqualTo(20);
    }

    @Test void changedFileIsRehashedEvenWhenSizeIsUnchanged()throws Exception{
        Path journal=root.resolve("observations.bin");Path file=root.resolve("A.java");
        Files.writeString(file,"class A{int a;}");
        var first=restarted(journal,0);String before=first.hash(file);first.flushObservations();

        Files.writeString(file,"class A{int b;}");
        var second=restarted(journal,0);
        String after=second.hash(file);
        assertThat(after).isNotEqualTo(before).isEqualTo(Hashing.sha256(file));
        assertThat(counter(second,"restored_stamp_mismatches")).isEqualTo(1);
        assertThat(counter(second,"hashes")).isEqualTo(1);
    }

    @Test void observationsInsideTheRacyWindowAreRehashedConservatively()throws Exception{
        Path journal=root.resolve("observations.bin");Path file=root.resolve("Racy.java");
        Files.writeString(file,"class Racy{}");
        var first=restarted(journal,FileStateRegistry.DEFAULT_RACY_WINDOW_NANOS);
        first.hash(file);first.flushObservations();

        // Observed immediately after the write: the stamp could be shared by a same-tick edit.
        var second=restarted(journal,FileStateRegistry.DEFAULT_RACY_WINDOW_NANOS);
        assertThat(second.hash(file)).isEqualTo(Hashing.sha256(file));
        assertThat(counter(second,"restored_racy_rejections")).isEqualTo(1);
        assertThat(counter(second,"restart_hash_reuse")).isZero();
        assertThat(counter(second,"hashes")).isEqualTo(1);
    }

    @Test void deletedAndReplacedFilesAreNotTrustedFromTheJournal()throws Exception{
        Path journal=root.resolve("observations.bin");Path file=root.resolve("B.java");
        Files.writeString(file,"class B{}");
        var first=restarted(journal,0);first.hash(file);first.flushObservations();

        Files.delete(file);
        var second=restarted(journal,0);
        assertThat(second.hash(file)).isEqualTo("missing");

        Files.writeString(file,"class B{}");
        var third=restarted(journal,0);
        assertThat(third.hash(file)).isEqualTo(Hashing.sha256(file));
        assertThat(counter(third,"restart_hash_reuse")).isZero();
        assertThat(counter(third,"hashes")).isEqualTo(1);
    }

    @Test void torn_or_corruptJournalLosesOnlyItsInvalidSuffix()throws Exception{
        Path journal=root.resolve("observations.bin");Path a=root.resolve("A.java"),b=root.resolve("B.java");
        Files.writeString(a,"class A{}");Files.writeString(b,"class B{}");
        var first=restarted(journal,0);first.hash(a);first.flushObservations();
        first.hash(b);first.flushObservations();
        byte[] bytes=Files.readAllBytes(journal);
        bytes[bytes.length-3]^=0x5a;Files.write(journal,bytes);

        var loaded=new FileObservationJournal(journal).load();
        assertThat(loaded.records()).containsOnlyKeys(a.toString());
        assertThat(loaded.corruptTail()).isEqualTo(1);

        var second=restarted(journal,0);
        assertThat(second.hash(a)).isEqualTo(Hashing.sha256(a));
        assertThat(second.hash(b)).isEqualTo(Hashing.sha256(b));
        assertThat(counter(second,"restart_hash_reuse")).isEqualTo(1);
        assertThat(counter(second,"hashes")).isEqualTo(1);

        Files.writeString(journal,"not a journal");
        assertThat(new FileObservationJournal(journal).load().records()).isEmpty();
    }

    @Test void providersWithoutChangeTimeAndInodeEvidenceAlwaysHash()throws Exception{
        Path zip=root.resolve("archive.zip");
        try(var fs=FileSystems.newFileSystem(URI.create("jar:"+zip.toUri()),Map.of("create","true"))){
            Path entry=fs.getPath("/Entry.java");Files.writeString(entry,"class Entry{}");
        }
        Path journal=root.resolve("observations.bin");
        try(var fs=FileSystems.newFileSystem(zip)){
            Path entry=fs.getPath("/Entry.java");
            var first=restarted(journal,0);first.hash(entry);first.hash(entry);first.flushObservations();
            assertThat(counter(first,"hashes")).isEqualTo(2);
            var second=restarted(journal,0);second.hash(entry);
            assertThat(counter(second,"restored_observations")).isZero();
            assertThat(counter(second,"hashes")).isEqualTo(1);
        }
    }

    @Test void compactionKeepsOnlyLiveObservations()throws Exception{
        Path journal=root.resolve("observations.bin");Path file=root.resolve("C.java");
        var registry=restarted(journal,0);
        for(int i=0;i<5;i++){Files.writeString(file,"class C{int v"+i+";}");registry.hash(file);registry.flushObservations();}
        var journalState=new FileObservationJournal(journal);
        var loaded=journalState.load();
        assertThat(loaded.records()).hasSize(1);
        journalState.compact(loaded.records());
        assertThat(new FileObservationJournal(journal).load().recordsRead()).isEqualTo(1);
        var restarted=restarted(journal,0);
        assertThat(restarted.hash(file)).isEqualTo(Hashing.sha256(file));
        assertThat(counter(restarted,"restart_hash_reuse")).isEqualTo(1);
    }
}
