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
        if(value.length==0)return new int[]{lastId(key)};
        int[] ids=new int[Math.min(256,Math.max(0,value.length-1))];
        int count=validate(key,value,ids);
        return count==ids.length?ids:Arrays.copyOf(ids,count);
    }
    /** Validate once, optionally materializing IDs for callers that need an array. */
    private static int validate(byte[] key,byte[] value,int[] ids){
        int maximum=lastId(key);
        if(value.length==0)return 1;
        if(value.length<2||value[0]!=0x7f)throw new IllegalStateException("Invalid posting encoding");
        int count=0,previous=0,delta=0,shift=0;
        for(int i=1;i<value.length;i++){
            int part=Byte.toUnsignedInt(value[i]);
            if(shift>28||(shift==28&&(part&0xf0)!=0))throw new IllegalStateException("Posting ID overflow");
            delta|=(part&0x7f)<<shift;
            if((part&0x80)!=0){shift+=7;continue;}
            long next=Integer.toUnsignedLong(previous)+Integer.toUnsignedLong(delta);
            if(count==256||next>Integer.MAX_VALUE||(count>0&&delta==0))throw new IllegalStateException("Invalid posting order");
            if(ids!=null)ids[count]=(int)next;count++;previous=(int)next;delta=0;shift=0;
        }
        if(shift!=0||count==0||previous!=maximum)throw new IllegalStateException("Truncated posting block");
        return count;
    }

    /** Reused by a merge run. Even blocks passed through intact are fully validated. */
    static final class Cursor {
        private byte[] value;
        private int count,remaining,position,previous,legacyId;
        void reset(byte[] key,byte[] value){
            remaining=0; // A failed reset cannot expose a partially validated block.
            count=validate(key,value,null);this.value=value;remaining=count;position=1;previous=0;
            legacyId=value.length==0?lastId(key):0;
        }
        int count(){return count;}
        int next(){
            if(remaining==0)throw new IllegalStateException("Posting cursor exhausted");remaining--;
            if(value.length==0)return legacyId;
            int delta=0,shift=0,part;
            do{part=Byte.toUnsignedInt(value[position++]);delta|=(part&127)<<shift;shift+=7;}while((part&128)!=0);
            previous+=delta;return previous;
        }
    }
}
