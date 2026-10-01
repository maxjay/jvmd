package dev.jvmd.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * XXH3-128 with seed 0 and the default secret, exactly {@code XXH3_128bits(data,len)} of the
 * reference {@code xxhash.h} (scalar path). One-shot only: no streaming, seeds or custom secrets.
 * Non-cryptographic; see {@link IdentityEncoder} for the threat model.
 */
final class Xxh3 {
    private Xxh3(){}

    private static final VarHandle LONG=MethodHandles.byteArrayViewVarHandle(long[].class,ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT=MethodHandles.byteArrayViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    private static final long P32_1=0x9E3779B1L,P32_2=0x85EBCA77L,P32_3=0xC2B2AE3DL;
    private static final long P64_1=0x9E3779B185EBCA87L,P64_2=0xC2B2AE3D27D4EB4FL,P64_3=0x165667B19E3779F9L,
            P64_4=0x85EBCA77C2B2AE63L,P64_5=0x27D4EB2F165667C5L,MX1=0x165667919E3779F9L,MX2=0x9FB21C651E98DF25L;
    private static final byte[] SECRET=decode(
            "b8fe6c3923a44bbe7c01812cf721ad1cded46de9839097db7240a4a4b7b3671f"
            +"cb79e64eccc0e578825ad07dccff7221b8084674f743248ee03590e6813a264c"
            +"3c2852bb91c300cb88d0658b1b532ea371644897a20df94e3819ef46a9deacd8"
            +"a8fa763fe39c343ff9dcbbc7c70b4f1d8a51e04bcdb45931c89f7ec9d9787364"
            +"eac5ac8334d3ebc3c581a0fffa1363eb170ddd51b7f0da49d316552629d4689e"
            +"2b16be587d47a1fc8ff8b8d17ad031ce45cb3a8f95160428afd7fbcabb4b407e");
    private static final int SECRET_SIZE=192,STRIPE=64,STRIPES_PER_BLOCK=(SECRET_SIZE-STRIPE)/8,BLOCK=STRIPE*STRIPES_PER_BLOCK;

    static Id128 hash128(byte[] b,int off,int len){
        if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
        if(len<=16)return upTo16(b,off,len);
        if(len<=128)return upTo128(b,off,len);
        if(len<=240)return upTo240(b,off,len);
        return large(b,off,len);
    }

    private static Id128 upTo16(byte[] b,int off,int len){
        if(len>8){
            long bitflipl=s64(32)^s64(40),bitfliph=s64(48)^s64(56);
            long inLo=r64(b,off),inHi=r64(b,off+len-8);
            long x=inLo^inHi^bitflipl;
            long mLo=x*P64_1,mHi=Math.unsignedMultiplyHigh(x,P64_1);
            mLo+=(long)(len-1)<<54;
            inHi^=bitfliph;
            mHi+=inHi+(inHi&0xFFFFFFFFL)*(P32_2-1);
            mLo^=Long.reverseBytes(mHi);
            long hLo=mLo*P64_2,hHi=Math.unsignedMultiplyHigh(mLo,P64_2)+mHi*P64_2;
            return new Id128(avalanche(hHi),avalanche(hLo));
        }
        if(len>=4){
            long input=(r32(b,off)&0xFFFFFFFFL)+((long)r32(b,off+len-4)<<32);
            long keyed=input^(s64(16)^s64(24));
            long m=P64_1+((long)len<<2);
            long lo=keyed*m,hi=Math.unsignedMultiplyHigh(keyed,m);
            hi+=lo<<1;lo^=hi>>>3;
            lo^=lo>>>35;lo*=MX2;lo^=lo>>>28;
            return new Id128(avalanche(hi),lo);
        }
        if(len>0){
            int c1=b[off]&0xFF,c2=b[off+(len>>1)]&0xFF,c3=b[off+len-1]&0xFF;
            int combinedl=c1<<16|c2<<24|c3|len<<8;
            int combinedh=Integer.rotateLeft(Integer.reverseBytes(combinedl),13);
            long bitflipl=(s32(0)^s32(4))&0xFFFFFFFFL,bitfliph=(s32(8)^s32(12))&0xFFFFFFFFL;
            return new Id128(avalanche64((combinedh&0xFFFFFFFFL)^bitfliph),avalanche64((combinedl&0xFFFFFFFFL)^bitflipl));
        }
        return new Id128(avalanche64(s64(80)^s64(88)),avalanche64(s64(64)^s64(72)));
    }

    private static Id128 upTo128(byte[] b,int off,int len){
        long lo=len*P64_1,hi=0;
        if(len>32){
            if(len>64){
                if(len>96){lo=mix(lo,b,off+48,off+len-64,96);hi=mix(hi,b,off+len-64,off+48,112);}
                lo=mix(lo,b,off+32,off+len-48,64);hi=mix(hi,b,off+len-48,off+32,80);
            }
            lo=mix(lo,b,off+16,off+len-32,32);hi=mix(hi,b,off+len-32,off+16,48);
        }
        lo=mix(lo,b,off,off+len-16,0);hi=mix(hi,b,off+len-16,off,16);
        return finish(lo,hi,len);
    }

    private static Id128 upTo240(byte[] b,int off,int len){
        long lo=len*P64_1,hi=0;
        int rounds=len/32;
        for(int i=0;i<4;i++){lo=mix(lo,b,off+32*i,off+32*i+16,32*i);hi=mix(hi,b,off+32*i+16,off+32*i,32*i+16);}
        lo=avalanche(lo);hi=avalanche(hi);
        for(int i=4;i<rounds;i++){
            int secret=3+32*(i-4);
            lo=mix(lo,b,off+32*i,off+32*i+16,secret);hi=mix(hi,b,off+32*i+16,off+32*i,secret+16);
        }
        int last=136-17-16;
        lo=mix(lo,b,off+len-16,off+len-32,last);hi=mix(hi,b,off+len-32,off+len-16,last+16);
        return finish(lo,hi,len);
    }

    private static Id128 finish(long lo,long hi,int len){
        return new Id128(-avalanche(lo*P64_1+hi*P64_4+len*P64_2),avalanche(lo+hi));
    }

    /** One {@code long[8]} accumulator is the only allocation besides the result. */
    private static Id128 large(byte[] b,int off,int len){
        long[] acc={P32_3,P64_1,P64_2,P64_3,P64_4,P32_2,P64_5,P32_1};
        int blocks=(len-1)/BLOCK;
        for(int n=0;n<blocks;n++){
            int block=off+n*BLOCK;
            for(int s=0;s<STRIPES_PER_BLOCK;s++)accumulate(acc,b,block+s*STRIPE,s*8);
            for(int i=0;i<8;i++){
                long a=acc[i];a^=a>>>47;a^=s64(SECRET_SIZE-STRIPE+8*i);acc[i]=a*P32_1;
            }
        }
        int tail=off+blocks*BLOCK,stripes=((len-1)-BLOCK*blocks)/STRIPE;
        for(int s=0;s<stripes;s++)accumulate(acc,b,tail+s*STRIPE,s*8);
        accumulate(acc,b,off+len-STRIPE,SECRET_SIZE-STRIPE-7);
        long lo=merge(acc,11,len*P64_1),hi=merge(acc,SECRET_SIZE-64-11,~(len*P64_2));
        return new Id128(hi,lo);
    }

    private static void accumulate(long[] acc,byte[] b,int in,int secret){
        for(int i=0;i<8;i++){
            long data=r64(b,in+8*i),key=data^s64(secret+8*i);
            acc[i^1]+=data;
            acc[i]+=(key&0xFFFFFFFFL)*(key>>>32);
        }
    }
    private static long merge(long[] acc,int secret,long start){
        long result=start;
        for(int i=0;i<4;i++)result+=fold(acc[2*i]^s64(secret+16*i),acc[2*i+1]^s64(secret+16*i+8));
        return avalanche(result);
    }
    /** One lane of the reference {@code XXH128_mix32B}; the other lane swaps the inputs and uses secret+16. */
    private static long mix(long acc,byte[] b,int in1,int in2,int secret){return (acc+mix16(b,in1,secret))^(r64(b,in2)+r64(b,in2+8));}
    private static long mix16(byte[] b,int in,int secret){return fold(r64(b,in)^s64(secret),r64(b,in+8)^s64(secret+8));}
    private static long fold(long a,long c){return a*c^Math.unsignedMultiplyHigh(a,c);}
    private static long avalanche(long h){h^=h>>>37;h*=MX1;return h^h>>>32;}
    private static long avalanche64(long h){h^=h>>>33;h*=P64_2;h^=h>>>29;h*=P64_3;return h^h>>>32;}
    private static long r64(byte[] b,int i){return (long)LONG.get(b,i);}
    private static int r32(byte[] b,int i){return (int)INT.get(b,i);}
    private static long s64(int i){return (long)LONG.get(SECRET,i);}
    private static int s32(int i){return (int)INT.get(SECRET,i);}
    private static byte[] decode(String hex){
        byte[] out=new byte[hex.length()/2];
        for(int i=0;i<out.length;i++)out[i]=(byte)(Character.digit(hex.charAt(2*i),16)<<4|Character.digit(hex.charAt(2*i+1),16));
        return out;
    }
}
