package dev.jvmd.boot.cold.local;

import java.nio.file.Path;
import java.util.*;

/**
 * The LOCAL cold boot's (unit, compiler context) jobs, as attribution batches: contexts in reactor
 * order, and within a context its units in path order. A unit a request needs moves its batch to
 * the front; nothing else attributes it.
 */
final class UnitQueue {
    /** One source file to attribute, with the compiler context that compiles it. */
    record Unit(Path file,Context context,String path,String binaryName) {
        Unit { file=file.toAbsolutePath().normalize(); }
    }

    private final ArrayDeque<List<Unit>> queue=new ArrayDeque<>();

    /** Queue the batches of one context, after everything already queued. */
    synchronized void addAll(Collection<List<Unit>> batches){for(var batch:batches)if(!batch.isEmpty())queue.add(List.copyOf(batch));}
    synchronized boolean isEmpty(){return queue.isEmpty();}

    /** The next batch, or an empty list when none is left. */
    synchronized List<Unit> next(){var batch=queue.pollFirst();return batch==null?List.of():batch;}

    /** Move the batch holding {@code file} to the front. False when it is not queued: built, being built, or not a unit. */
    synchronized boolean prioritize(Path file){
        file=file.toAbsolutePath().normalize();
        for(var iterator=queue.iterator();iterator.hasNext();){
            var batch=iterator.next();
            for(var unit:batch)if(unit.file().equals(file)){iterator.remove();queue.addFirst(batch);return true;}
        }
        return false;
    }
}
