package dev.jvmd.index.rocks;

import java.util.Arrays;

/** Shared validation for bounded, delta-encoded posting lists in runs and SSTs. */
final class PostingCodec {
    private PostingCodec(){}
    static int lastId(byte[] key){
        int id=0;
        for(int i=key.length-8;i<key.length;i++){
            int digit=Character.digit(key[i],16);if(digit<0)throw new IllegalStateException("Invalid posting ID");
            id=(id<<4)|digit;
        }
        return id;
    }
    static int[] decode(byte[] key,byte[] value){
        int maximum=lastId(key);
        if(value.length==0)return new int[]{maximum};
        if(value.length<2||value[0]!=0x7f)throw new IllegalStateException("Invalid posting encoding");
        int[] ids=new int[256];int count=0,previous=0,delta=0,shift=0;
        for(int i=1;i<value.length;i++){
            int part=Byte.toUnsignedInt(value[i]);
            if(shift>28||(shift==28&&(part&0xf0)!=0))throw new IllegalStateException("Posting ID overflow");
            delta|=(part&0x7f)<<shift;
            if((part&0x80)!=0){shift+=7;continue;}
            long next=Integer.toUnsignedLong(previous)+Integer.toUnsignedLong(delta);
            if(count==256||next>Integer.MAX_VALUE||(count>0&&delta==0))throw new IllegalStateException("Invalid posting order");
            ids[count++]=(int)next;previous=(int)next;delta=0;shift=0;
        }
        if(shift!=0||count==0||previous!=maximum)throw new IllegalStateException("Truncated posting block");
        return Arrays.copyOf(ids,count);
    }
}
