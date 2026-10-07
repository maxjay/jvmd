package dev.jvmd.boot.cold.stage2;

import com.sun.source.tree.RequiresTree;
import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Descriptor-only lint. It never changes the ordinary classpath task's module graph. */
final class ModuleLint {
    private ModuleLint() { }

    static void requires(Context context,JCTree.JCModuleDecl declaration,List<Path> path,Path jdk,
                         Charset charset,int release) throws IOException {
        if(declaration.getDirectives().stream().noneMatch(d->d instanceof RequiresTree))return;
        var lint=Lint.instance(context).augment(declaration.sym);
        if(!lint.isEnabled(Lint.LintCategory.REQUIRES_AUTOMATIC)
                && !lint.isEnabled(Lint.LintCategory.REQUIRES_TRANSITIVE_AUTOMATIC))return;
        // Use javac's own module-location naming and multi-release selection, rather than infer names from jar basenames.
        try(var files=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,Locale.ROOT,charset)) {
            files.handleOption("--system",List.of(jdk.toString()).iterator());
            files.handleOption("--multi-release",List.of(String.valueOf(release)).iterator());
            files.setLocationFromPaths(StandardLocation.MODULE_PATH,path);
            for(var item:declaration.getDirectives())if(item instanceof JCTree.JCRequires required) {
                var name=required.moduleName.toString();
                if(files.getLocationForModule(StandardLocation.SYSTEM_MODULES,name)!=null)continue;
                var location=files.getLocationForModule(StandardLocation.MODULE_PATH,name);
                if(location==null || files.getJavaFileForInput(location,"module-info",JavaFileObject.Kind.CLASS)!=null)continue;
                // Match Check.checkModuleRequires, including its transitive-category fallback.
                boolean transitive=required.isTransitive()
                        && lint.isEnabled(Lint.LintCategory.REQUIRES_TRANSITIVE_AUTOMATIC);
                var category=transitive?Lint.LintCategory.REQUIRES_TRANSITIVE_AUTOMATIC:Lint.LintCategory.REQUIRES_AUTOMATIC;
                lint.logIfEnabled(required.moduleName.pos(),new JCDiagnostic.LintWarning(category,"compiler",
                        transitive?"requires.transitive.automatic":"requires.automatic"));
            }
        }
    }
}
