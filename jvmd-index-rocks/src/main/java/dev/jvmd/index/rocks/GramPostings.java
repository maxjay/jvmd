package dev.jvmd.index.rocks;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded gram-to-ID accumulation before sorting; callers supply increasing symbol IDs. */
final class GramPostings {
    private static final long ENTRY_BYTES=1280; // Includes the fixed ID array, key and LRU entry.
    private static final class GramKey {
        private long value;
        GramKey(long value){this.value=value;}
        @Override public int hashCode(){return GramSet.hash(value);}
        @Override public boolean equals(Object other){return other instanceof GramKey key&&value==key.value;}
    }
    private static final class Block {final int[] ids=new int[256];int size;}
    private final LinkedHashMap<GramKey,Block> blocks=new LinkedHashMap<>(16,.75f,true);
    // Only this lookup probe is mutated; keys inserted into the map never change.
    private final GramKey probe=new GramKey(0);
    private final byte[] encoded=new byte[1+256*5];
    private final SstSorter sorter;
    private final long budget;
    private long peakBytes,occurrences,flushed;
    GramPostings(SstSorter sorter,long budget){
        if(budget<ENTRY_BYTES)throw new IllegalArgumentException("Gram budget cannot hold one block");
        this.sorter=sorter;this.budget=budget;
    }
    void add(long gram,int id)throws Exception{
        occurrences++;
        probe.value=gram;var block=blocks.get(probe);
        if(block==null){
            while((blocks.size()+1)*ENTRY_BYTES>budget){var first=blocks.pollFirstEntry();flush(first.getKey().value,first.getValue());}
            block=new Block();blocks.put(new GramKey(gram),block);peakBytes=Math.max(peakBytes,blocks.size()*ENTRY_BYTES);
        }
        if(block.size>0&&block.ids[block.size-1]>=id)throw new IllegalArgumentException("Gram IDs must increase");
        block.ids[block.size++]=id;if(block.size==256)flush(gram,block);
    }
    void finish()throws Exception{for(var entry:blocks.entrySet())flush(entry.getKey().value,entry.getValue());blocks.clear();}
    private void flush(long gram,Block block)throws Exception{
        if(block.size==0)return;
        encoded[0]=0x7f;int previous=0,position=1;
        for(int i=0;i<block.size;i++){
            int delta=block.ids[i]-previous;previous=block.ids[i];
            do{int part=delta&127;delta>>>=7;encoded[position++]=(byte)(part|(delta==0?0:128));}while(delta!=0);
        }
        String last=Integer.toHexString(previous);
        sorter.add(("8|gram|"+GramSet.text(gram)+"|"+"0".repeat(8-last.length())+last).getBytes(StandardCharsets.UTF_8),Arrays.copyOf(encoded,position));
        flushed++;block.size=0;
    }
    long peakBytes(){return peakBytes;}
    long occurrences(){return occurrences;}
    long flushed(){return flushed;}
}
