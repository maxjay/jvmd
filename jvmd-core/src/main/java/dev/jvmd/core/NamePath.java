package dev.jvmd.core;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

/** Implements 4.8: name paths with erased overload parameters, without choosing an ambiguous match. */
public record NamePath(String path,List<String> parameters,boolean identity) {
    public NamePath { parameters=parameters==null?null:List.copyOf(parameters); }
    public static NamePath parse(String value){
        if(value==null||value.isBlank()||value.length()>8192)throw RpcException.invalid("Invalid name path");
        if(value.startsWith("maven ")||value.startsWith("local "))return new NamePath(value,null,true);
        int open=value.indexOf('(');String path=open<0?value:value.substring(0,open);
        List<String> parameters=null;
        if(open>=0){
            if(!value.endsWith(")")||value.indexOf('(',open+1)>=0)throw RpcException.invalid("Invalid overload suffix");
            String args=value.substring(open+1,value.length()-1);parameters=new ArrayList<>();
            if(!args.isBlank())for(String argument:args.split(",",-1)){
                String type=argument.trim();String base=type;while(base.endsWith("[]"))base=base.substring(0,base.length()-2);
                if(base.equals("void")||!qualified(base,'.')||base.isEmpty())throw RpcException.invalid("Invalid erased parameter type: "+type);
                parameters.add(type);
            }
        }
        if(path.isBlank()||!Arrays.stream(path.split("/",-1)).allMatch(part->qualified(part,'.')))throw RpcException.invalid("Invalid name path");
        return new NamePath(path,parameters,false);
    }
    private static boolean qualified(String value,char separator){
        if(value.isEmpty())return false;for(String part:value.split(java.util.regex.Pattern.quote(String.valueOf(separator)),-1)){
            if(part.isEmpty()||!Character.isJavaIdentifierStart(part.codePointAt(0)))return false;
            for(int offset=Character.charCount(part.codePointAt(0));offset<part.length();){int code=part.codePointAt(offset);if(!Character.isJavaIdentifierPart(code))return false;offset+=Character.charCount(code);}
        }return true;
    }
    public String leaf(){int slash=Math.max(path.lastIndexOf('$'),Math.max(path.lastIndexOf('/'),path.lastIndexOf('.')));return path.substring(slash+1);}
    public boolean matches(Map<String,Object> symbol){
        if(identity)return path.equals(symbol.get("scip"));
        String candidate=Objects.toString(symbol.get("name_path"),"");int open=candidate.indexOf('(');
        String base=open<0?candidate:candidate.substring(0,open);
        if(!(base.equals(path)||base.endsWith("."+path)||base.endsWith("/"+path)||Objects.equals(symbol.get("name"),path)))return false;
        if(parameters==null)return true;
        List<String> actual;
        Object descriptor=symbol.get("erased_descriptor");
        if(descriptor instanceof String text&&text.startsWith("(")){
            try{actual=Arrays.stream(MethodTypeDesc.ofDescriptor(text).parameterArray()).map(NamePath::qualifiedType).toList();}
            catch(IllegalArgumentException invalid){return false;}
        }else{
            // Source snapshots always carry descriptors; legacy index rows may only have a name path.
            if(open<0||!candidate.endsWith(")"))return false;
            String args=candidate.substring(open+1,candidate.length()-1);actual=args.isEmpty()?List.of():Arrays.asList(args.split(","));
        }
        if(actual.size()!=parameters.size())return false;
        for(int i=0;i<actual.size();i++){
            String wanted=parameters.get(i).replace('$','.'),got=actual.get(i).trim().replace('$','.');
            if(!got.equals(wanted)&&!(wanted.indexOf('.')<0&&got.endsWith("."+wanted)))return false;
        }return true;
    }
    private static String qualifiedType(ClassDesc type){
        String descriptor=type.descriptorString();int dimensions=0;while(descriptor.charAt(dimensions)=='[')dimensions++;
        String base=descriptor.substring(dimensions);String name=base.startsWith("L")?base.substring(1,base.length()-1).replace('/','.').replace('$','.')
                :switch(base){case "Z"->"boolean";case "B"->"byte";case "S"->"short";case "C"->"char";case "I"->"int";case "J"->"long";case "F"->"float";case "D"->"double";default->"void";};
        return name+"[]".repeat(dimensions);
    }
}
