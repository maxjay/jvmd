import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Loaded into each measured JVM with -javaagent:agent.jar=SOCKET. Every {@code ?} written to
 * the socket is answered with one line: the JVM's total heap bytes allocated so far by
 * all threads (live and terminated), or -1 when the JVM cannot report it. Every {@code T} is
 * answered with one JSON line: that total and, per live platform thread, its id, name and
 * cumulative allocated bytes. Virtual threads are not listed: what they allocate is counted on
 * their carrier threads ({@code ForkJoinPool-*}).
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
                        String reply = command == 'T' ? threads() : command == '?' ? Long.toString(allocated()) : null;
                        if (reply != null) channel.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
                    }
                    request.clear();
                }
            } catch (Exception closed) {
                // The harness reconnects per server; a closed peer just waits for the next one.
            }
        }
    }

    /** The probe's own thread (allocation-probe) is listed too, so its share of a window can be told apart. */
    private static String threads() {
        try {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long[] ids = bean.getAllThreadIds();
            long[] bytes = bean.getThreadAllocatedBytes(ids);
            var infos = bean.getThreadInfo(ids, 0);
            var out = new StringBuilder("{\"threads\":[");
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] == null || bytes[i] < 0) continue;
                if (out.charAt(out.length() - 1) != '[') out.append(',');
                out.append('[').append(ids[i]).append(",\"").append(infos[i].getThreadName().replace("\\", "\\\\").replace("\"", "\\\"")).append("\",").append(bytes[i]).append(']');
            }
            return out.append("],\"total\":").append(allocated()).append('}').toString();
        } catch (LinkageError | ClassCastException unavailable) {
            return "{\"threads\":[],\"total\":-1}";
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
}
