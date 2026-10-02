package dev.jvmd.boot.cold.local;

import java.nio.file.Path;
import java.util.*;

/**
 * The LOCAL cold boot's (unit, compiler context) jobs, in reactor order. A unit a request needs is
 * moved to the front; nothing else attributes it.
 */
final class UnitQueue {
    /** One source file to attribute, with the compiler context that compiles it. */
    record Unit(Path file,Context context,String path,String binaryName) {
        Unit { file=file.toAbsolutePath().normalize(); }
    }

    private final ArrayDeque<Unit> queue=new ArrayDeque<>();

    synchronized void addAll(Collection<Unit> units){queue.addAll(units);}
    synchronized boolean isEmpty(){return queue.isEmpty();}
    synchronized int size(){return queue.size();}

    /**
     * The next batch: the unit at the front and up to {@code max - 1} further queued units of the same
     * compiler context, in queue order.
     */
    synchronized List<Unit> next(int max){
        var first=queue.pollFirst();if(first==null)return List.of();
        var batch=new ArrayList<Unit>();batch.add(first);
        for(var iterator=queue.iterator();iterator.hasNext()&&batch.size()<max;){
            var unit=iterator.next();
            if(unit.context().key().equals(first.context().key())){batch.add(unit);iterator.remove();}
        }
        return batch;
    }

    /** Move {@code file}'s job to the front. False when it is not queued: built, being built, or not a unit. */
    synchronized boolean prioritize(Path file){
        file=file.toAbsolutePath().normalize();
        for(var iterator=queue.iterator();iterator.hasNext();){
            var unit=iterator.next();
            if(unit.file().equals(file)){iterator.remove();queue.addFirst(unit);return true;}
        }
        return false;
    }
}
