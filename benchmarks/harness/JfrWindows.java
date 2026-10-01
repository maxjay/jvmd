import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import jdk.jfr.consumer.*;

/**
 * Allocation inside time windows of a JFR recording: {@code java JfrWindows.java <windows.tsv> <top> <file.jfr>...}
 * where each line of windows.tsv is {@code label<TAB>startEpochMs<TAB>endEpochMs}. Prints, per window,
 * the sampled allocation (jdk.ObjectAllocationSample weights) by thread name and the top stacks by
 * sampled bytes, as Markdown. Samples are statistical: weights estimate bytes, they do not count them.
 */
public class JfrWindows {
    record Window(String label,long start,long end,Map<String,Long> threads,Map<String,Long> stacks,long[] total,Map<String,Long> causes,Map<String,Long> cpu) { }
    private static boolean matches(List<Pattern> patterns,List<String> methods){
        for(var pattern:patterns)if(methods.stream().noneMatch(m->pattern.matcher(m).find()))return false;
        return true;
    }
    /** Inclusive attribution: a sample counts for every cause whose frame is anywhere on its stack. */
    static final Map<String,List<Pattern>> CAUSES=new LinkedHashMap<>();
    /** A cause matches when every one of its patterns (separated by " && ") matches some frame of the stack. */
    private static void cause(String name,String patterns){CAUSES.put(name,Arrays.stream(patterns.split(" && ")).map(Pattern::compile).toList());}
    static{
        cause("memo capture (AttributedMemos.memoize/capture)","AttributedMemos\\.(memoize|capture)$");
        cause("memo drain (AttributedMemos.drain, SCC)","AttributedMemos\\.drain$");
        cause("memo write (AttributedMemos.write, writer task)","AttributedMemos\\.(write|lambda\\$write\\$\\d+)$");
        cause("S0 package parse (packageIdentity, SourceNamespaces)","AttributedMemos\\.packageIdentity$|SourceNamespaces\\.");
        cause("memo store put (SemanticMemoStore.put)","SemanticMemoStore\\.put$");
        cause("memo restore (AttributedMemos.restore)","AttributedMemos\\.(restore|resolve)$");
        cause("P_diag (DiagnosticProjection.of)","DiagnosticProjection\\.");
        cause("binary P_diag (BinaryProjections)","BinaryProjections\\.");
        cause("machine index publish (IndexStore/RocksIndexStore.publish*)","IndexStore\\.publish");
        cause("javac task creation (JavacTool.getTask)","JavacTool\\.getTask$");
        cause("javac attribution (comp.Attr)","javac\\.comp\\.Attr\\.");
        cause("Bindings.capture","Bindings\\.capture$");
        // Identities of AttributedMemos (inventory): where each is computed.
        cause("id: static key (attributedStaticKey, StaticInputs)","AttributedMemos\\.(attributedStaticKey|staticInputs)$|StaticInputs\\.");
        cause("id: content hash in capture (Hashing.sha256 under capture)","Hashing\\.sha256$ && AttributedMemos\\.capture$");
        cause("id: package files (packageFiles, members)","AttributedMemos\\.(packageFiles|members)$");
        cause("id: consulted packages regex","AttributedMemos\\.consultedPackages$");
        cause("id: negatives (negatives, NamespaceResolutionProofs)","AttributedMemos\\.negatives$|NamespaceResolutionProofs\\.");
        cause("id: absence identity","AttributedMemos\\.absenceIdentity$");
        cause("id: static-import bindings","AttributedMemos\\.staticImportBindings$");
        cause("id: reactor classes, class-package","AttributedMemos\\.(reactorClasses|classPackageIdentity|perClassDirectories)$");
        cause("id: processor resources, config","AttributedMemos\\.(processorResources|configIdentity)$");
        cause("id: current hash (restore)","AttributedMemos\\.currentHash$");
        cause("id: current P_diag / early cutoff (currentProjection)","AttributedMemos\\.currentProjection$");
        cause("id: result JSON (capture)","ObjectMapper\\.writeValueAsBytes$ && AttributedMemos\\.capture$");
        cause("id: SCC Tarjan (drain minus write)","AttributedMemos\\.drain$");
        cause("id: memo lookup + decode","SemanticMemoStore\\.(lookup|decode)$");
        cause("id: certificate encode (writer)","SemanticMemoStore\\.encode$");
    }

    public static void main(String[] args)throws Exception{
        int top=Integer.parseInt(args[1]);var windows=new ArrayList<Window>();
        // Lines with the same label are one aggregate window (e.g. every hover-after-edit request of a run).
        var byLabel=new LinkedHashMap<String,Window>();var intervals=new ArrayList<Object[]>();
        for(String line:Files.readAllLines(Path.of(args[0]))){
            if(line.isBlank())continue;var parts=line.split("\t");
            var window=byLabel.computeIfAbsent(parts[0],label->new Window(label,Long.parseLong(parts[1]),Long.parseLong(parts[2]),new HashMap<>(),new HashMap<>(),new long[4],new LinkedHashMap<>(),new LinkedHashMap<>()));
            window.total()[2]+=Long.parseLong(parts[2])-Long.parseLong(parts[1]);intervals.add(new Object[]{window,Long.parseLong(parts[1]),Long.parseLong(parts[2])});
        }
        windows.addAll(byLabel.values());
        for(int i=2;i<args.length;i++)try(var file=new RecordingFile(Path.of(args[i]))){
            while(file.hasMoreEvents()){
                var event=file.readEvent();
                if(event.getEventType().getName().equals("jdk.ExecutionSample")){
                    long when=event.getStartTime().toEpochMilli();
                    var methods=new ArrayList<String>();
                    if(event.getStackTrace()!=null)for(var frame:event.getStackTrace().getFrames())methods.add(frame.getMethod().getType().getName()+"."+frame.getMethod().getName());
                    for(var interval:intervals)if(when>=(long)interval[1]&&when<=(long)interval[2]){
                        var window=(Window)interval[0];window.total()[3]++;
                        for(var cause:CAUSES.entrySet())if(matches(cause.getValue(),methods))window.cpu().merge(cause.getKey(),1L,Long::sum);
                        break;
                    }
                    continue;
                }
                if(!event.getEventType().getName().equals("jdk.ObjectAllocationSample"))continue;
                long at=event.getStartTime().toEpochMilli();
                var hit=new LinkedHashSet<Window>();
                for(var interval:intervals)if(at>=(long)interval[1]&&at<=(long)interval[2])hit.add((Window)interval[0]);
                for(var window:hit){
                    long weight=event.getLong("weight");var thread=event.getThread("eventThread");
                    String name=thread==null?"?":thread.getJavaName()==null?"?":thread.getJavaName();
                    window.threads().merge(name.replaceAll("-\\d+$","-N"),weight,Long::sum);
                    window.stacks().merge(stack(event.getStackTrace(),event.getClass("objectClass")),weight,Long::sum);
                    window.total()[0]+=weight;window.total()[1]++;
                    var methods=new ArrayList<String>();
                    if(event.getStackTrace()!=null)for(var frame:event.getStackTrace().getFrames())methods.add(frame.getMethod().getType().getName()+"."+frame.getMethod().getName());
                    for(var cause:CAUSES.entrySet())if(matches(cause.getValue(),methods))window.causes().merge(cause.getKey(),weight,Long::sum);
                }
            }
        }
        // Labels starting "row:" are one compact table row each: sampled MB in the window, then per cause.
        var rows=windows.stream().filter(w->w.label().startsWith("row:")).toList();
        if(!rows.isEmpty()){
            System.out.println("| sample | ms | sampled MB | "+String.join(" | ",CAUSES.keySet().stream().map(c->c.replaceAll(" \\(.*",""))
                    .toList())+" |\n|---|---:|---:|"+"---:|".repeat(CAUSES.size()));
            for(var w:rows)System.out.printf(Locale.ROOT,"| %s | %d | %.2f | %s |%n",w.label().substring(4),w.total()[2],w.total()[0]/1048576.0,
                    String.join(" | ",CAUSES.keySet().stream().map(c->String.format(Locale.ROOT,"%.2f",w.causes().getOrDefault(c,0L)/1048576.0)).toList()));
            System.out.println();
        }
        for(var window:windows){
            if(window.label().startsWith("row:"))continue;
            System.out.printf(Locale.ROOT,"#### %s%n%n%s%d samples, %.1f MB sampled%n%n",window.label(),window.total()[2]>1_000_000_000L?"":window.total()[2]+" ms in window, ",window.total()[1],window.total()[0]/1048576.0);
            System.out.println("| thread | sampled MB | share |\n|---|---:|---:|");
            window.threads().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(12)
                    .forEach(e->System.out.printf(Locale.ROOT,"| %s | %.2f | %.0f%% |%n",e.getKey(),e.getValue()/1048576.0,100.0*e.getValue()/Math.max(1,window.total()[0])));
            System.out.printf(Locale.ROOT,"%n| cause (inclusive: a sample counts for every cause on its stack) | sampled MB | share | CPU samples (%d in window) |%n|---|---:|---:|---:|%n",window.total()[3]);
            for(String cause:CAUSES.keySet())System.out.printf(Locale.ROOT,"| %s | %.2f | %.0f%% | %d |%n",cause,window.causes().getOrDefault(cause,0L)/1048576.0,100.0*window.causes().getOrDefault(cause,0L)/Math.max(1,window.total()[0]),window.cpu().getOrDefault(cause,0L));
            System.out.println("\n| # | sampled MB | share | stack (allocated type; innermost first, JVMD and javac frames) |\n|---:|---:|---:|---|");
            int[] rank={0};
            window.stacks().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(top)
                    .forEach(e->System.out.printf(Locale.ROOT,"| %d | %.2f | %.0f%% | %s |%n",++rank[0],e.getValue()/1048576.0,100.0*e.getValue()/Math.max(1,window.total()[0]),e.getKey()));
            System.out.println();
        }
    }
    private static final Pattern INTERESTING=Pattern.compile("^(dev\\.jvmd\\.|com\\.sun\\.tools\\.javac\\.|org\\.rocksdb\\.|com\\.fasterxml\\.)");
    /** The allocated type, then the innermost frames up to and including the first 4 JVMD frames. */
    private static String stack(RecordedStackTrace trace,RecordedClass type){
        var frames=new ArrayList<String>();int jvmd=0;
        if(trace!=null)for(var frame:trace.getFrames()){
            var method=frame.getMethod();String owner=method.getType().getName();
            String name=owner.substring(owner.lastIndexOf('.')+1)+"."+method.getName()+":"+frame.getLineNumber();
            if(frames.size()<3||INTERESTING.matcher(owner).find())frames.add(name);
            if(owner.startsWith("dev.jvmd.")&&++jvmd==4)break;
            if(frames.size()>=10)break;
        }
        return "`"+(type==null?"?":type.getName())+"` ← "+String.join(" ← ",frames);
    }
}
