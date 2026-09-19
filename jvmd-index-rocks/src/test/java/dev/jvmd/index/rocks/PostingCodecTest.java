package dev.jvmd.index.rocks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PostingCodecTest {
    @Test void reusableCursorMatchesArrayDecoderAcrossBlockSizesAndWidths(){
        var cursor=new PostingCodec.Cursor();var random=new Random(711);
        for(int count=1;count<=256;count++){
            int[] expected=new int[count];expected[0]=count%2==0?0:random.nextInt(1000000);
            for(int i=1;i<count;i++)expected[i]=expected[i-1]+1+random.nextInt(1000000);
            byte[] key=key(expected[count-1]),value=encode(expected);
            assertThat(PostingCodec.decode(key,value)).containsExactly(expected);cursor.reset(key,value);
            assertThat(cursor.count()).isEqualTo(count);
            for(int id:expected)assertThat(cursor.next()).isEqualTo(id);
            assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
        }
        for(byte[] value:List.of(new byte[0],encode(new int[]{Integer.MAX_VALUE}))){
            cursor.reset(key(Integer.MAX_VALUE),value);assertThat(cursor.next()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Test void cursorRejectsCorruptionBeforeAnyPostingCanPassThrough(){
        var cursor=new PostingCodec.Cursor();
        var malformed=new ArrayList<>(List.of(new byte[]{0x7f},new byte[]{0x7f,(byte)0x80},new byte[]{0x7f,0,0},
                new byte[]{0x7e,0},new byte[]{0x7f,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x10},new byte[]{0x7f,1}));
        malformed.add(encode(java.util.stream.IntStream.range(0,257).toArray()));
        for(byte[] value:malformed){
            cursor.reset(key(0),new byte[]{0x7f,0});
            assertThatThrownBy(()->cursor.reset(key(0),value)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
        }
    }
    private static byte[] key(int id){return String.format(Locale.ROOT,"%08x",id).getBytes(StandardCharsets.US_ASCII);}
    private static byte[] encode(int[] ids){
        var output=new ByteArrayOutputStream();output.write(0x7f);int previous=0;
        for(int id:ids){int delta=id-previous;previous=id;do{int part=delta&127;delta>>>=7;output.write(part|(delta==0?0:128));}while(delta!=0);}
        return output.toByteArray();
    }
}
