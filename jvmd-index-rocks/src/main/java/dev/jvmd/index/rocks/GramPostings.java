package dev.jvmd.index.rocks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded gram-to-ID accumulation before sorting; callers supply increasing symbol IDs. */
final class GramPostings {
    private static final long ENTRY_BYTES=1280; // Includes the fixed ID array and short UTF-16 gram key.
    private static final class Block {final int[] ids=new int[256];int size;}
    private final LinkedHashMap<String,Block> blocks=new LinkedHashMap<>(16,.75f,true);
    private final SstSorter sorter;
    private final String namespace;
    private final long budget;
    private long peakBytes,occurrences,flushed;
    GramPostings(SstSorter sorter,String namespace,long budget){this.sorter=sorter;this.namespace=namespace;this.budget=budget;}
    void add(String gram,int id)throws Exception{
        occurrences++;
        var block=blocks.get(gram);
        if(block==null){
            while((blocks.size()+1)*ENTRY_BYTES>budget){var first=blocks.pollFirstEntry();flush(first.getKey(),first.getValue());}
            block=new Block();blocks.put(gram,block);peakBytes=Math.max(peakBytes,blocks.size()*ENTRY_BYTES);
        }
        if(block.size>0&&block.ids[block.size-1]>=id)throw new IllegalArgumentException("Gram IDs must increase");
        block.ids[block.size++]=id;if(block.size==256)flush(gram,block);
    }
    void finish()throws Exception{for(var entry:blocks.entrySet())flush(entry.getKey(),entry.getValue());blocks.clear();}
    private void flush(String gram,Block block)throws Exception{
        if(block.size==0)return;
        var encoded=new ByteArrayOutputStream(block.size+8);encoded.write(0x7f);int previous=0;
        for(int i=0;i<block.size;i++){
            int delta=block.ids[i]-previous;previous=block.ids[i];
            do{int part=delta&127;delta>>>=7;encoded.write(part|(delta==0?0:128));}while(delta!=0);
        }
        String last=Integer.toHexString(previous);
        sorter.add((namespace+"|8|gram|"+gram+"|"+"0".repeat(8-last.length())+last).getBytes(StandardCharsets.UTF_8),encoded.toByteArray());
        flushed++;block.size=0;
    }
    long peakBytes(){return peakBytes;}
    long occurrences(){return occurrences;}
    long flushed(){return flushed;}
}
