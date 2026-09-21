package dev.jvmd.index;

import dev.jvmd.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned binary values for mutable facts. String references are local to one record. */
public final class FactCodec {
    private FactCodec() {}
    public static byte[] encode(Object value)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(0x4a564601);write(out,Json.MAPPER.valueToTree(value),new HashMap<>());}
        return bytes.toByteArray();
    }
    public static <T> T decode(byte[] bytes,Class<T> type)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x4a564601)throw new IOException("Unsupported fact format");
            Object value=read(in,new ArrayList<>(),0);if(in.available()!=0)throw new IOException("Trailing fact data");
            return Json.MAPPER.convertValue(value,type);
        }
    }
    private static void string(DataOutputStream out,String value,Map<String,Integer> strings)throws IOException {
        Integer id=strings.get(value);if(id!=null){out.writeInt(id);return;}
        strings.put(value,strings.size());byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(-bytes.length-1);out.write(bytes);
    }
    private static String string(DataInputStream in,List<String> strings)throws IOException {
        int n=in.readInt();if(n>=0){if(n>=strings.size())throw new IOException("Invalid string reference");return strings.get(n);}
        long length=-(long)n-1;if(length>in.available())throw new IOException("Truncated fact string");
        String value=new String(in.readNBytes((int)length),StandardCharsets.UTF_8);strings.add(value);return value;
    }
    private static void write(DataOutputStream out,JsonNode value,Map<String,Integer> strings)throws IOException {
        if(value.isNull()){out.writeByte(0);return;}
        if(value.isTextual()){out.writeByte(1);string(out,value.textValue(),strings);return;}
        if(value.isBoolean()){out.writeByte(value.booleanValue()?2:3);return;}
        if(value.isIntegralNumber()){out.writeByte(4);out.writeLong(value.longValue());return;}
        if(value.isFloatingPointNumber()){out.writeByte(5);out.writeDouble(value.doubleValue());return;}
        out.writeByte(value.isArray()?6:7);out.writeInt(value.size());
        if(value.isArray())for(var child:value)write(out,child,strings);
        else for(var entry:value.properties()){string(out,entry.getKey(),strings);write(out,entry.getValue(),strings);}
    }
    private static Object read(DataInputStream in,List<String> strings,int depth)throws IOException {
        if(depth>128)throw new IOException("Fact nesting limit");
        int tag=in.readUnsignedByte();return switch(tag){
            case 0->null;case 1->string(in,strings);case 2->true;case 3->false;
            case 4->in.readLong();case 5->in.readDouble();
            case 6,7->{int tagSize=in.readInt();if(tagSize<0||tagSize>in.available())throw new IOException("Invalid fact count");
                if(tag==6){var list=new ArrayList<Object>(tagSize);for(int i=0;i<tagSize;i++)list.add(read(in,strings,depth+1));yield list;}
                var map=new LinkedHashMap<String,Object>();for(int i=0;i<tagSize;i++)map.put(string(in,strings),read(in,strings,depth+1));yield map;}
            default->throw new IOException("Invalid fact tag");
        };
    }
}
