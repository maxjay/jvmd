import java.nio.file.Path;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Streams jdk.ObjectAllocationOutsideTLAB events of at least THRESHOLD bytes from a JFR file and prints one
 * JSON line per allocation: epoch millis, size, class, the innermost JVMD frame and the top frames.
 *   java Humongous.java RECORDING.jfr [THRESHOLD]
 */
public final class Humongous {
    public static void main(String[] args) throws Exception {
        long threshold = args.length > 1 ? Long.parseLong(args[1]) : 512 * 1024;
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                if (!e.getEventType().getName().equals("jdk.ObjectAllocationOutsideTLAB")) continue;
                long size = e.getLong("allocationSize");
                if (size < threshold) continue;
                var frames = e.getStackTrace() == null ? java.util.List.<RecordedFrame>of() : e.getStackTrace().getFrames();
                String site = "?";
                var top = new StringBuilder();
                for (int i = 0; i < frames.size(); i++) {
                    var m = frames.get(i).getMethod();
                    String name = m.getType().getName() + "." + m.getName();
                    if (i < 4) top.append(i == 0 ? "" : " < ").append(name);
                    if (site.equals("?") && name.startsWith("dev.jvmd.")) site = name;
                }
                if (site.equals("?") && !frames.isEmpty()) site = frames.get(0).getMethod().getType().getName() + "." + frames.get(0).getMethod().getName();
                System.out.println("{\"t\":" + e.getStartTime().toEpochMilli() + ",\"size\":" + size + ",\"class\":\""
                        + e.getClass("objectClass").getName() + "\",\"site\":\"" + site + "\",\"stack\":\"" + top + "\"}");
            }
        }
    }
}
