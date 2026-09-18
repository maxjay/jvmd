package dev.jvmd.prototype;

import java.nio.charset.StandardCharsets;
import java.util.*;

interface PrototypeStore extends AutoCloseable {
    record Entry(byte[] key,byte[] value) {
        Entry(String key,byte[] value){this(key.getBytes(StandardCharsets.UTF_8),value);}
    }
    void publish(List<Entry> entries)throws Exception;
    byte[] get(byte[] key)throws Exception;
    List<Entry> prefix(byte[] prefix,int limit)throws Exception;
    long storageBytes()throws Exception;
    String name();
}
