package dev.jvmd.index.layer.local;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Symbol;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/** Task-local direct-name questions after attribution, with javac's language-model visibility filter. */
final class NamedMembers {
    static final int FIELD = 1, TYPE = 2;
    private final Map<TypeElement,Map<String,Integer>> answers = new IdentityHashMap<>();
    long queries, visited;
    boolean has(TypeElement owner, String name, int kind) {
        return (answers.computeIfAbsent(owner, ignored -> new HashMap<>()).computeIfAbsent(name, ignored -> find(owner,name)) & kind) != 0;
    }
    private int find(TypeElement owner, String name) {
        queries++;
        int answer = 0;
        if (owner instanceof Symbol.ClassSymbol symbol) {
            symbol.apiComplete();
            for (var member : symbol.members().getSymbolsByName(symbol.name.table.fromString(name), Scope.LookupKind.NON_RECURSIVE)) {
                visited++;
                member.apiComplete();
                if (member.owner != symbol || member.kind == Kinds.Kind.ERR || (member.flags() & Flags.SYNTHETIC) != 0) continue;
                if (member instanceof VariableElement) answer |= FIELD;
                if (member instanceof TypeElement) answer |= TYPE;
            }
        } else {
            for (var member : owner.getEnclosedElements()) { visited++; if (member.getSimpleName().contentEquals(name)) {
                if (member instanceof VariableElement) answer |= FIELD;
                if (member instanceof TypeElement) answer |= TYPE;
            }}
        }
        return answer;
    }
}
