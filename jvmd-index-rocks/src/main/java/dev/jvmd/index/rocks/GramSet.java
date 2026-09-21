package dev.jvmd.index.rocks;

import java.util.Arrays;

/** Reusable set of one-to-three UTF-16 code units, without per-occurrence strings. */
final class GramSet {
    private long[] table=new long[64],values=new long[32];
    private int size;

    void clear(){Arrays.fill(table,0);size=0;}
    int size(){return size;}
    long get(int index){return values[index];}
    boolean contains(long gram){return table[slot(gram,table)]!=0;}

    void add(String value,int from){
        for(int length=1;length<=3;length++)for(int i=from;i+length<=value.length();i++){
            long gram=(long)length<<48;
            for(int j=0;j<length;j++)gram|=(long)value.charAt(i+j)<<(j*16);
            add(gram);
        }
    }
    private void add(long gram){
        int slot=slot(gram,table);if(table[slot]!=0)return;
        if(size==values.length){
            values=Arrays.copyOf(values,size*2);table=new long[table.length*2];
            for(int i=0;i<size;i++)table[slot(values[i],table)]=values[i];
            slot=slot(gram,table);
        }
        table[slot]=gram;values[size++]=gram;
    }
    private static int slot(long gram,long[] table){
        int slot=hash(gram)&(table.length-1);
        while(table[slot]!=0&&table[slot]!=gram)slot=(slot+1)&(table.length-1);
        return slot;
    }
    static int hash(long gram){
        gram=(gram^(gram>>>33))*0xff51afd7ed558ccdL;
        gram=(gram^(gram>>>33))*0xc4ceb9fe1a85ec53L;
        return (int)(gram^(gram>>>33));
    }
    static String text(long gram){
        char[] value=new char[(int)(gram>>>48)];
        for(int i=0;i<value.length;i++)value[i]=(char)(gram>>>(i*16));
        return new String(value);
    }
}
