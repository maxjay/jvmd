import java.lang.management.ManagementFactory;
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
                    request.clear();
                    channel.write(ByteBuffer.wrap((allocated() + "\n").getBytes(StandardCharsets.US_ASCII)));
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
}
