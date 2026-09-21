package dev.jvmd.analyzer;

import com.sun.tools.javac.main.Arguments;
import com.sun.tools.javac.main.DelegatingJavaFileManager;
import com.sun.tools.javac.platform.PlatformDescription;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Predicate;
import javax.tools.JavaFileManager;

/** One actor-confined release platform, with the same lifetime as its javac context. */
final class ReleasePlatformCache implements AutoCloseable {
    private Context context;
    private PlatformDescription platform;
    private JavaFileManager files;
    private List<String> validatedOptions;
    private String release;
    private long initializations,reuses;

    void prepare(List<String> options){
        if(context==null||!options.equals(validatedOptions))return;
        // JavacTaskPool drops Arguments after each task. Its first task performed javac's
        // complete option validation; only the identical option list can take this path.
        context.put(Arguments.argsKey,(Context.Factory<Arguments>)CachedArguments::new);
    }
    void capture(Context current,List<String> options){
        var description=current.get(PlatformDescription.class);
        if(description==null||description==platform)return;
        close();
        context=current;platform=description;validatedOptions=List.copyOf(options);
        release=Options.instance(current).get("--release");
        // The description already owns an open ct.sym filesystem. Obtain an independently
        // configured platform manager once, preserving javac's release-specific API view.
        files=description.getFileManager();initializations++;
    }
    long initializations(){return initializations;}
    long reuses(){return reuses;}

    private final class CachedArguments extends Arguments {
        private final Context current;
        CachedArguments(Context current){super(current);this.current=current;}
        @Override public boolean handleReleaseOptions(Predicate<Iterable<String>> additionalOptions){
            var options=Options.instance(current);
            if(!release.equals(options.get("--release")))return super.handleReleaseOptions(additionalOptions);
            options.put("-source",platform.getSourceVersion());
            options.put("-target",platform.getTargetVersion());
            current.put(PlatformDescription.class,platform);
            if(!additionalOptions.test(platform.getAdditionalOptions()))return false;
            DelegatingJavaFileManager.installReleaseFileManager(current,files,current.get(JavaFileManager.class));
            reuses++;return true;
        }
    }
    @Override public void close(){
        try{
            try{if(files!=null)files.close();}
            finally{if(platform!=null)platform.close();}
        }catch(IOException e){throw new UncheckedIOException(e);}
        finally{context=null;platform=null;files=null;validatedOptions=null;release=null;}
    }
}
