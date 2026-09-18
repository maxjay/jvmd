package dev.jvmd.index;

import java.lang.constant.MethodTypeDesc;
import java.util.*;

/** External Maven/path identity kept separate from reusable artifact facts. */
public record ArtifactContext(String gav,String kind,String path) {
    public ArtifactContext {
        Objects.requireNonNull(gav);Objects.requireNonNull(kind);Objects.requireNonNull(path);
        if(gav.isBlank()||kind.isBlank()||path.isBlank())throw new IllegalArgumentException("artifact context");
    }

    public String scip(ArtifactIndexFormat.SymbolRecord symbol) {
        String[] parts=gav.split(":",3);
        if(parts.length!=3)throw new IllegalArgumentException("Invalid GAV: "+gav);
        String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";
        String owner=symbol.fqn().replace('.','/').replace('$','#')+"#";
        if(symbol.key().equals(symbol.fqn()))return prefix+owner;
        if(symbol.kind().equals("method")||symbol.kind().equals("ctor")){
            var type=MethodTypeDesc.ofDescriptor(symbol.descriptor());
            String returnIdentity=symbol.metadataJson().contains("\"scip_return_disambiguated\":true")?
                    ";return="+Signatures.qualified(type.returnType()):"";
            return prefix+owner+(symbol.kind().equals("ctor")?"<init>":symbol.name())+"("+
                    String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+returnIdentity+").";
        }
        return prefix+owner+symbol.name()+".";
    }

    public static String namePath(ArtifactIndexFormat.SymbolRecord symbol) {
        String owner=symbol.fqn().replace('$','/');
        if(symbol.key().equals(symbol.fqn()))return owner;
        if(symbol.kind().equals("method")||symbol.kind().equals("ctor")){
            var type=MethodTypeDesc.ofDescriptor(symbol.descriptor());
            return owner+"/"+symbol.name()+"("+String.join(",",Arrays.stream(type.parameterArray())
                    .map(p->p.displayName().replace('$','.')).toList())+")";
        }
        return owner+"/"+symbol.name();
    }
}
