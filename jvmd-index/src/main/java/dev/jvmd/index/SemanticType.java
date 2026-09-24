package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;

/** Detached Java type identity. No javac-owned object may cross this boundary. */
public sealed interface SemanticType permits SemanticType.Primitive,SemanticType.Declared,SemanticType.Variable,
        SemanticType.Array,SemanticType.Executable,SemanticType.Wildcard,SemanticType.Intersection,SemanticType.Unknown {
    String display();

    default SemanticType substitute(Map<String,SemanticType> substitutions){return this;}

    /** Stable binary identity reused by fact hashing and later type interning/persistence. */
    default Hash256 identity(){
        if(this instanceof Primitive value)return CanonicalDigestWriter.digest("semantic-type-v1","primitive",value.name());
        if(this instanceof Declared value)return CanonicalDigestWriter.digest("semantic-type-v1","declared",value.symbolId(),value.name(),
                value.arguments().stream().map(SemanticType::identity).toList());
        if(this instanceof Variable value)return CanonicalDigestWriter.digest("semantic-type-v1","variable",value.symbolId(),value.name());
        if(this instanceof Array value)return CanonicalDigestWriter.digest("semantic-type-v1","array",value.component().identity());
        if(this instanceof Executable value)return CanonicalDigestWriter.digest("semantic-type-v1","executable",
                value.parameters().stream().map(SemanticType::identity).toList(),value.returns().identity(),
                value.thrown().stream().map(SemanticType::identity).toList());
        if(this instanceof Wildcard value)return CanonicalDigestWriter.digest("semantic-type-v1","wildcard",
                value.extendsBound()==null?null:value.extendsBound().identity(),value.superBound()==null?null:value.superBound().identity());
        if(this instanceof Intersection value)return CanonicalDigestWriter.digest("semantic-type-v1","intersection",
                value.bounds().stream().map(SemanticType::identity).toList());
        var value=(Unknown)this;return CanonicalDigestWriter.digest("semantic-type-v1","unknown",value.text());
    }

    record Primitive(String name) implements SemanticType {
        public Primitive { Objects.requireNonNull(name); }
        @Override public String display(){return name;}
    }

    record Declared(String symbolId,String name,List<SemanticType> arguments) implements SemanticType {
        public Declared {
            Objects.requireNonNull(symbolId);Objects.requireNonNull(name);
            arguments=List.copyOf(arguments);
        }
        @Override public String display(){
            return arguments.isEmpty()?name:name+"<"+String.join(", ",arguments.stream().map(SemanticType::display).toList())+">";
        }
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){
            if(arguments.isEmpty())return this;
            var replaced=arguments.stream().map(value->value.substitute(substitutions)).toList();
            return replaced.equals(arguments)?this:new Declared(symbolId,name,replaced);
        }
    }

    record Variable(String symbolId,String name) implements SemanticType {
        public Variable { Objects.requireNonNull(symbolId);Objects.requireNonNull(name); }
        @Override public String display(){return name;}
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){return substitutions.getOrDefault(symbolId,this);}
    }

    record Array(SemanticType component) implements SemanticType {
        public Array { Objects.requireNonNull(component); }
        @Override public String display(){return component.display()+"[]";}
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){
            var replaced=component.substitute(substitutions);return replaced==component?this:new Array(replaced);
        }
    }

    record Executable(List<SemanticType> parameters,SemanticType returns,List<SemanticType> thrown) implements SemanticType {
        public Executable {parameters=List.copyOf(parameters);Objects.requireNonNull(returns);thrown=List.copyOf(thrown);}
        @Override public String display(){return "("+String.join(", ",parameters.stream().map(SemanticType::display).toList())+") -> "+returns.display();}
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){
            var p=parameters.stream().map(value->value.substitute(substitutions)).toList();
            var r=returns.substitute(substitutions);var t=thrown.stream().map(value->value.substitute(substitutions)).toList();
            return p.equals(parameters)&&r==returns&&t.equals(thrown)?this:new Executable(p,r,t);
        }
    }

    record Wildcard(SemanticType extendsBound,SemanticType superBound) implements SemanticType {
        @Override public String display(){
            if(extendsBound!=null)return "? extends "+extendsBound.display();
            if(superBound!=null)return "? super "+superBound.display();return "?";
        }
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){
            var e=extendsBound==null?null:extendsBound.substitute(substitutions);
            var s=superBound==null?null:superBound.substitute(substitutions);
            return e==extendsBound&&s==superBound?this:new Wildcard(e,s);
        }
    }

    record Intersection(List<SemanticType> bounds) implements SemanticType {
        public Intersection {bounds=List.copyOf(bounds);}
        @Override public String display(){return String.join(" & ",bounds.stream().map(SemanticType::display).toList());}
        @Override public SemanticType substitute(Map<String,SemanticType> substitutions){
            var replaced=bounds.stream().map(value->value.substitute(substitutions)).toList();
            return replaced.equals(bounds)?this:new Intersection(replaced);
        }
    }

    record Unknown(String text) implements SemanticType {
        public Unknown {text=Objects.requireNonNullElse(text,"?");}
        @Override public String display(){return text;}
    }
}
