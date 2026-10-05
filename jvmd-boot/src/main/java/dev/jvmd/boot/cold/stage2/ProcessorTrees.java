package dev.jvmd.boot.cold.stage2;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

/** Observes syntax escaping through javac's native Trees instance, including unwrapped processing environments. */
final class ProcessorTrees extends JavacTrees {
    private final ProcessorHost host;

    ProcessorTrees(Context context, ProcessorHost host) {
        super(context);
        this.host = host;
    }

    @Override public JCTree getTree(Element element, AnnotationMirror annotation, AnnotationValue value) {
        host.syntaxRead("Trees.getTree");
        return super.getTree(ProcessorReads.nativeObject(element), ProcessorReads.nativeObject(annotation), ProcessorReads.nativeObject(value));
    }

    @Override public TreePath getPath(Element element, AnnotationMirror annotation, AnnotationValue value) {
        // Even a path to a declaration exposes its whole compilation unit and therefore executable bodies.
        host.syntaxRead("Trees.getPath");
        return super.getPath(ProcessorReads.nativeObject(element), ProcessorReads.nativeObject(annotation), ProcessorReads.nativeObject(value));
    }
}
