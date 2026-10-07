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
    private final Map<TypeElement,Map<String,Answer>> answers = new IdentityHashMap<>();
    private static final class Answer {
        final java.util.List<javax.lang.model.element.Element> members;
        int known,value;
        Answer(java.util.List<javax.lang.model.element.Element> members) {this.members=members;}
        boolean has(int kind) {
            if((known & kind)==0) {
                for(var member:members) {
                    if(kind==FIELD && !(member instanceof VariableElement) || kind==TYPE && !(member instanceof TypeElement))continue;
                    // A field question must not complete a same-name nested class merely to classify it.
                    if(member instanceof Symbol symbol) {
                        symbol.apiComplete();
                        if(symbol.kind==Kinds.Kind.ERR || (symbol.flags() & Flags.SYNTHETIC)!=0)continue;
                    }
                    value|=kind;
                }
                known|=kind;
            }
            return (value & kind)!=0;
        }
    }
    long queries, visited;
    boolean has(TypeElement owner, String name, int kind) {
        return answers.computeIfAbsent(owner, ignored -> new HashMap<>()).computeIfAbsent(name, ignored -> find(owner,name)).has(kind);
    }
    private Answer find(TypeElement owner, String name) {
        queries++;
        var answer=new java.util.ArrayList<javax.lang.model.element.Element>();
        if (owner instanceof Symbol.ClassSymbol symbol) {
            symbol.apiComplete();
            for (var member : symbol.members().getSymbolsByName(symbol.name.table.fromString(name), Scope.LookupKind.NON_RECURSIVE)) {
                visited++;
                if(member.owner==symbol)answer.add(member);
            }
        } else {
            for (var member : owner.getEnclosedElements()) { visited++; if (member.getSimpleName().contentEquals(name)) {
                answer.add(member);
            }}
        }
        return new Answer(answer);
    }
}
