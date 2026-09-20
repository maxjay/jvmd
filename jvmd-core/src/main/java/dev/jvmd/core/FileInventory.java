package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Reconciliation tolerates concurrent removal; permission and other I/O failures remain visible. */
public final class FileInventory {
    private FileInventory(){}
    public static List<Path> matching(Path root,String suffix)throws IOException{
        return matching(root,suffix,Integer.MAX_VALUE);
    }
    /** Bounded admission checks can stop before materializing a large source inventory. */
    public static List<Path> matching(Path root,String suffix,int limit)throws IOException{
        if(limit<1)throw new IllegalArgumentException("limit");
        var result=new TreeSet<Path>();
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes){
                if(path.toString().endsWith(suffix)&&(attributes.isRegularFile()||Files.isRegularFile(path)))result.add(path);
                return result.size()>=limit?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path,IOException error)throws IOException{
                if(error instanceof NoSuchFileException)return FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{
                if(error!=null&&!(error instanceof NoSuchFileException))throw error;
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(result);
    }
}
