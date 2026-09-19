package dev.jvmd.index.rocks;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GramSetTest {
    @Test void compactGramsMatchSubstringSetsIncludingEveryUtf16CodeUnit(){
        var actual=new GramSet();var random=new Random(912);
        var texts=new ArrayList<>(List.of("", "aaaaa", "a\0", "\0a\0", "İType/field", "𐐀Type/𐐁field"));
        for(int i=0;i<100;i++){
            char[] text=new char[i*7];for(int j=0;j<text.length;j++)text[j]=(char)random.nextInt(65536);
            texts.add(new String(text));
        }
        for(String text:texts){
            actual.clear();int from=text.isEmpty()?0:random.nextInt(text.length()+1);
            var expected=new HashSet<String>();
            for(int length=1;length<=3;length++)for(int i=from;i+length<=text.length();i++)expected.add(text.substring(i,i+length));
            actual.add(text,from);actual.add(text,from);var decoded=new HashSet<String>();
            for(int i=0;i<actual.size();i++){assertThat(actual.contains(actual.get(i))).isTrue();decoded.add(GramSet.text(actual.get(i)));}
            assertThat(actual.size()).isEqualTo(expected.size());assertThat(decoded).isEqualTo(expected);
        }
        actual.clear();actual.add("a",0);assertThat(actual.size()).isEqualTo(1);
        long old=actual.get(0);actual.clear();assertThat(actual.contains(old)).isFalse();
    }
}
