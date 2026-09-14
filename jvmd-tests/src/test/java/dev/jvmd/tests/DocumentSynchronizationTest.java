package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 9: ordered UTF-16 change events with transactional version checks. */
@Tag("phase-9")
class DocumentSynchronizationTest {
    @TempDir Path root;
    private static Documents.Position at(int line,int character){return new Documents.Position(line,character);}
    @Test void incrementalChangesUseThePreviousEventAndPreserveCrLfAndSurrogates()throws Exception{
        Path file=root.resolve("Text.java");var documents=new Documents();documents.open(file,"// \uD83D\uDE00\r\nclass Text {}\r\n",7);
        assertThat(Documents.offset(documents.text(file),at(0,5))).isEqualTo(5);
        assertThatThrownBy(()->Documents.offset(documents.text(file),at(0,4))).isInstanceOf(RpcException.class);
        assertThatThrownBy(()->Documents.offset(documents.text(file),at(0,6))).isInstanceOf(RpcException.class);
        documents.change(file,8,List.of(new Documents.Change(new Documents.Range(at(1,6),at(1,10)),"Renamed"),new Documents.Change(new Documents.Range(at(1,15),at(1,15)),"int value;")));
        assertThat(documents.text(file)).isEqualTo("// \uD83D\uDE00\r\nclass Renamed {int value;}\r\n");assertThat(documents.version(file)).isEqualTo(8);assertThat(Files.exists(file)).isFalse();
        assertThatThrownBy(()->documents.change(file,8,List.of(new Documents.Change(null,"stale")))).isInstanceOf(RpcException.class);
        String before=documents.text(file);
        assertThatThrownBy(()->documents.change(file,9,List.of(new Documents.Change(null,"first"),new Documents.Change(new Documents.Range(at(2,0),at(2,1)),"bad")))).isInstanceOf(RpcException.class);
        assertThat(documents.text(file)).isEqualTo(before);assertThat(documents.version(file)).isEqualTo(8);
        documents.close(file);assertThat(documents.status().get("open_documents")).isEqualTo(0);
    }
}
