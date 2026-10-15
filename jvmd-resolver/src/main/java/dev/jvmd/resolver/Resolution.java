package dev.jvmd.resolver;

import java.util.List;
import java.util.Map;

/**
 * Implements 4.3: a serialized dependency graph, never a cached Maven effective model.
 *
 * @param classpaths   per {@code gav:main|test|runtime}, the files a compiler would read: reactor modules only once built
 * @param compileOrder per {@code gav:main|test}, the compile classpath in Maven's order with every reactor module present, built or
 *                     not, as {@code reactor:<gav>}; other entries are absolute file paths. The stage 2 project model reads this
 */
public record Resolution(String root, List<Module> modules, List<Node> nodes, List<Edge> edges,
                         Map<String, List<String>> classpaths, List<String> warnings,
                         String fingerprint, boolean offline, boolean cached, Map<String, List<String>> compileOrder) {
    public static final String REACTOR = "reactor:";

    public Resolution { if (compileOrder == null) compileOrder = Map.of(); }
    public Resolution(String root, List<Module> modules, List<Node> nodes, List<Edge> edges, Map<String, List<String>> classpaths,
                      List<String> warnings, String fingerprint, boolean offline, boolean cached) {
        this(root, modules, nodes, edges, classpaths, warnings, fingerprint, offline, cached, Map.of());
    }
    /** Implements 4.3 and 4.6: module paths derived by Maven's model builder. */
    public record Module(String gav, String directory, String packaging, List<String> sources,
                         List<String> testSources, String classes, String testClasses,
                         String release, List<String> dependencies,List<String> compilerOptions,List<String> testCompilerOptions,Processing processing,Processing testProcessing) {
        public Module { if(processing==null)processing=Processing.none();if(testProcessing==null)testProcessing=Processing.none(); }
        public Module(String gav,String directory,String packaging,List<String> sources,List<String> testSources,String classes,String testClasses,String release,List<String> dependencies,List<String> compilerOptions,List<String> testCompilerOptions){this(gav,directory,packaging,sources,testSources,classes,testClasses,release,dependencies,compilerOptions,testCompilerOptions,Processing.none(),Processing.none());}
        public Module(String gav,String directory,String packaging,List<String> sources,List<String> testSources,String classes,String testClasses,String release,List<String> dependencies){
            this(gav,directory,packaging,sources,testSources,classes,testClasses,release,dependencies,List.of("--release",release==null||release.isBlank()?"25":release),List.of("--release",release==null||release.isBlank()?"25":release));
        }
    }
    /** Implements phase 5: resolved processor inputs, never loaded by the daemon. */
    public record Processing(List<String> path,List<String> names,boolean enabled,boolean lombok,String generatedDirectory) {
        public static Processing none(){return new Processing(List.of(),List.of(),false,false,"");}
    }
    /** Implements 4.3: version winners and retained conflict losers. */
    public record Node(String id, String gav, String extension, String classifier, String scope,
                       String path, String winner, String reason, boolean optional) { }
    /** Implements 4.4: module/artifact dependency edges with scope. */
    public record Edge(String src, String dst, String scope) { }
    public Resolution cachedCopy() { return new Resolution(root, modules, nodes, edges, classpaths, warnings, fingerprint, offline, true, compileOrder); }
    public List<String> classpath() { return classpaths.values().stream().flatMap(List::stream).distinct().toList(); }
}
