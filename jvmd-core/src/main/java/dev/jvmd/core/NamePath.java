package dev.jvmd.core;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

/** Implements 4.8: nested name paths with erased overloads and explicit ambiguous matches. */
public final class NamePath {
    private record Segment(String name,List<String> parameters) { }
    private final String path;
    private final List<Segment> segments;
    private final boolean identity;
    private NamePath(String path,List<Segment> segments,boolean identity){this.path=path;this.segments=List.copyOf(segments);this.identity=identity;}
    public String path(){return path;}
    public boolean identity(){return identity;}
    public List<String> parameters(){return segments.isEmpty()?null:segments.getLast().parameters();}
    public static NamePath parse(String value){
        if(value==null||value.isBlank()||value.length()>8192)throw RpcException.invalid("Invalid name path");
        if(value.startsWith("maven ")||value.startsWith("local "))return new NamePath(value,List.of(),true);
        var segments=new ArrayList<Segment>();
        for(String part:value.split("/",-1)){
            int open=part.indexOf('(');String name=open<0?part:part.substring(0,open);List<String> parameters=null;
            if(!qualified(name))throw RpcException.invalid("Invalid name path");
            if(open>=0){
                if(!part.endsWith(")")||part.indexOf('(',open+1)>=0)throw RpcException.invalid("Invalid overload suffix");
                String args=part.substring(open+1,part.length()-1);parameters=new ArrayList<>();
                if(!args.isBlank())for(String argument:args.split(",",-1)){
                    String type=argument.trim(),base=type;while(base.endsWith("[]"))base=base.substring(0,base.length()-2);
                    if(base.equals("void")||!qualified(base))throw RpcException.invalid("Invalid erased parameter type: "+type);parameters.add(type);
                }
            }segments.add(new Segment(name,parameters==null?null:List.copyOf(parameters)));
        }
        return new NamePath(String.join("/",segments.stream().map(Segment::name).toList()),segments,false);
    }
    private static boolean qualified(String value){
        if(value.isEmpty())return false;for(String part:value.split("\\.",-1)){
            if(part.isEmpty()||!Character.isJavaIdentifierStart(part.codePointAt(0)))return false;
            for(int offset=Character.charCount(part.codePointAt(0));offset<part.length();){int code=part.codePointAt(offset);if(!Character.isJavaIdentifierPart(code))return false;offset+=Character.charCount(code);}
        }return true;
    }
    public String leaf(){int slash=Math.max(path.lastIndexOf('$'),Math.max(path.lastIndexOf('/'),path.lastIndexOf('.')));return path.substring(slash+1);}
    public boolean matches(Map<String,Object> symbol){
        if(identity)return path.equals(symbol.get("scip"));
        String candidate=Objects.toString(symbol.getOrDefault("qualified_name_path",symbol.get("name_path")),"");
        List<Segment> actual;
        try{actual=parse(candidate).segments;}catch(RpcException invalid){return parameters()==null&&segments.size()==1&&Objects.equals(symbol.get("name"),path);}
        if(actual.size()<segments.size())return false;
        int base=actual.size()-segments.size();
        for(int index=0;index<segments.size();index++){
            var wanted=segments.get(index);var got=actual.get(base+index);
            if(!got.name().equals(wanted.name())&&!(index==0&&got.name().endsWith("."+wanted.name())))return false;
            if(wanted.parameters()==null)continue;
            List<String> types=got.parameters();Object descriptor=symbol.get("erased_descriptor");
            if(index==segments.size()-1&&descriptor instanceof String text&&text.startsWith("(")){
                try{types=Arrays.stream(MethodTypeDesc.ofDescriptor(text).parameterArray()).map(NamePath::qualifiedType).toList();}catch(IllegalArgumentException invalid){return false;}
            }
            if(types==null||types.size()!=wanted.parameters().size())return false;
            for(int i=0;i<types.size();i++){
                String expected=wanted.parameters().get(i).replace('$','.'),value=types.get(i).replace('$','.');
                if(!value.equals(expected)&&!(expected.indexOf('.')<0&&value.endsWith("."+expected)))return false;
            }
        }return true;
    }
    private static String qualifiedType(ClassDesc type){
        String descriptor=type.descriptorString();int dimensions=0;while(descriptor.charAt(dimensions)=='[')dimensions++;
        String base=descriptor.substring(dimensions);String name=base.startsWith("L")?base.substring(1,base.length()-1).replace('/','.').replace('$','.')
                :switch(base){case "Z"->"boolean";case "B"->"byte";case "S"->"short";case "C"->"char";case "I"->"int";case "J"->"long";case "F"->"float";case "D"->"double";default->"void";};
        return name+"[]".repeat(dimensions);
    }
}
