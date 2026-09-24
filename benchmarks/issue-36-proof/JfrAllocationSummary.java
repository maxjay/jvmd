import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

/**
 * Tiny PR-only JFR reader. It deliberately avoids the JDK jfr CLI formatter because Temurin
 * 25.0.4.1 crashes formatting some JVMD allocation-sample method descriptors.
 */
public final class JfrAllocationSummary {
    private record Key(String objectClass,String site) { }
    private static final class Count {
        long samples;
        long bytes;
    }

    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("usage: JfrAllocationSummary <recording.jfr> <output.tsv>");
        var totals=new LinkedHashMap<Key,Count>();
        long samples=0,bytes=0;
        try(var recording=new RecordingFile(Path.of(args[0]))){
            while(recording.hasMoreEvents()){
                RecordedEvent event=recording.readEvent();
                if(!event.getEventType().getName().equals("jdk.ObjectAllocationSample"))continue;
                String objectClass=className(event);
                long weight=event.hasField("weight")?event.getLong("weight"):0L;
                String site=topJavaSite(event);
                var count=totals.computeIfAbsent(new Key(objectClass,site),ignored->new Count());
                count.samples++;count.bytes+=weight;samples++;bytes+=weight;
            }
        }
        var rows=new ArrayList<>(totals.entrySet());
        rows.sort(Comparator.<Map.Entry<Key,Count>>comparingLong(e->e.getValue().bytes).reversed()
                .thenComparing(e->e.getKey().objectClass)
                .thenComparing(e->e.getKey().site));
        try(BufferedWriter out=Files.newBufferedWriter(Path.of(args[1]))){
            out.write("total_samples\t"+samples+"\n");
            out.write("total_weight_bytes\t"+bytes+"\n");
            out.write("samples\tweight_bytes\tobject_class\ttop_java_site\n");
            for(var entry:rows){
                out.write(Long.toString(entry.getValue().samples));out.write('\t');
                out.write(Long.toString(entry.getValue().bytes));out.write('\t');
                out.write(clean(entry.getKey().objectClass));out.write('\t');
                out.write(clean(entry.getKey().site));out.write('\n');
            }
        }
    }

    private static String className(RecordedEvent event){
        try{
            RecordedClass value=event.getClass("objectClass");
            return value==null?"<unknown>":value.getName();
        }catch(RuntimeException ignored){return "<unknown>";}
    }

    private static String topJavaSite(RecordedEvent event){
        var stack=event.getStackTrace();
        if(stack==null)return "<unknown>";
        for(RecordedFrame frame:stack.getFrames()){
            if(!frame.isJavaFrame())continue;
            try{
                RecordedMethod method=frame.getMethod();
                if(method==null)return "<unknown>";
                String owner=method.getType()==null?"<unknown>":method.getType().getName();
                return owner+"."+method.getName();
            }catch(RuntimeException ignored){return "<unknown>";}
        }
        return "<unknown>";
    }

    private static String clean(String value){
        return value==null?"<unknown>":value.replace('\t',' ').replace('\n',' ').replace('\r',' ');
    }
}
