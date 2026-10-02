package dev.jvmd.tests;

import dev.jvmd.core.Documents;
import java.util.Random;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** A text's line index answers every offset, including those outside it, as a scan of the text does. */
@Tag("phase-3")
class DocumentLinesTest {
    @Test void everyOffsetHasThePositionAScanGives(){
        var random=new Random(7);
        for(String text:new String[]{"","\n","\n\n","a","a\n","\nb","one\ntwo\r\nthree\n\nfour"}){
            var lines=new Documents.Lines(text);
            for(long offset=-2;offset<=text.length()+2;offset++)
                assertThat(lines.position(offset)).as(text+"@"+offset).isEqualTo(Documents.position(text,offset));
        }
        for(int round=0;round<50;round++){
            var text=new StringBuilder();int length=random.nextInt(400);
            for(int i=0;i<length;i++)text.append(random.nextInt(6)==0?'\n':(char)('a'+random.nextInt(26)));
            var lines=new Documents.Lines(text.toString());
            for(long offset=-1;offset<=length+1;offset++)
                assertThat(lines.position(offset)).isEqualTo(Documents.position(text.toString(),offset));
        }
    }
}
