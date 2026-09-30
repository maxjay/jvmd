import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Loaded into each measured JVM with -javaagent:agent.jar=SOCKET. Every byte written to
 * the socket is answered with one line: the JVM's total heap bytes allocated so far by
 * all threads (live and terminated), or -1 when the JVM cannot report it.
 *
 * Two further request bytes serve memory investigations and are never sent by the benchmark:
 * 's' answers one JSON line with a memory snapshot, 'p' does the same and then resets every
 * memory pool's peak, so the next snapshot's peaks cover exactly the interval in between.
 * The snapshot reports the probe thread's own allocation so readers can subtract it.
 */
public final class AllocationAgent {
    public static void premain(String socket) throws Exception {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(Path.of(socket)));
        var thread = new Thread(() -> serve(server), "allocation-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void serve(ServerSocketChannel server) {
        while (true) {
            try (var channel = server.accept()) {
                var request = ByteBuffer.allocate(64);
                while (channel.read(request) > 0) {
                    request.flip();
                    while (request.hasRemaining()) {
                        byte command = request.get();
                        String line;
                        try {
                            line = command == 's' || command == 'p' ? snapshot(command == 'p') : Long.toString(allocated());
                        } catch (OutOfMemoryError exhausted) {
                            line = command == 's' || command == 'p' ? "null" : "-1"; // Keep the connection: later requests may succeed.
                        }
                        channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.US_ASCII)));
                    }
                    request.clear();
                }
            } catch (Exception closed) {
                // The harness reconnects per server; a closed peer just waits for the next one.
            }
        }
    }

    private static long allocated() {
        try {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            return bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean.getTotalThreadAllocatedBytes() : -1;
        } catch (LinkageError | ClassCastException unavailable) {
            return -1; // Runtime image without jdk.management.
        }
    }

    private static long ownAllocated() {
        try {
            return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
        } catch (LinkageError | ClassCastException unavailable) {
            return -1;
        }
    }

    private static String snapshot(boolean resetPeaks) {
        long ownBefore = ownAllocated();
        var out = new StringBuilder(2048);
        out.append("{\"t_ns\":").append(System.nanoTime()).append(",\"allocated\":").append(allocated());
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        usage(out.append(",\"heap\":"), heap);
        usage(out.append(",\"non_heap\":"), nonHeap);
        out.append(",\"pools\":{");
        boolean first = true;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(pool.getName()).append("\":{\"heap\":").append(pool.getType() == MemoryType.HEAP);
            usage(out.append(",\"usage\":"), pool.getUsage());
            usage(out.append(",\"peak\":"), pool.getPeakUsage());
            if (pool.getCollectionUsage() != null) usage(out.append(",\"after_gc\":"), pool.getCollectionUsage());
            out.append('}');
        }
        out.append("},\"buffers\":{");
        first = true;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(pool.getName()).append("\":{\"count\":").append(pool.getCount())
                    .append(",\"capacity\":").append(pool.getTotalCapacity()).append(",\"used\":").append(pool.getMemoryUsed()).append('}');
        }
        out.append("},\"gc\":{");
        first = true;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(gc.getName()).append("\":{\"count\":").append(gc.getCollectionCount()).append(",\"ms\":").append(gc.getCollectionTime()).append('}');
        }
        var threads = ManagementFactory.getThreadMXBean();
        out.append("},\"threads\":").append(threads.getThreadCount())
                .append(",\"classes\":").append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        if (resetPeaks) for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        long ownAfter = ownAllocated();
        out.append(",\"probe_allocated\":").append(ownAfter).append(",\"probe_snapshot_bytes\":").append(ownAfter - ownBefore).append('}');
        return out.toString();
    }

    private static void usage(StringBuilder out, MemoryUsage u) {
        out.append("{\"used\":").append(u.getUsed()).append(",\"committed\":").append(u.getCommitted()).append(",\"max\":").append(u.getMax()).append('}');
    }
}
