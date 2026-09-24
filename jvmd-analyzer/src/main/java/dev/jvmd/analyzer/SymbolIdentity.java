package dev.jvmd.analyzer;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import dev.jvmd.index.SemanticFact;
import java.util.*;
import java.util.function.Function;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
/** Implements 4.2: javac-resolved SCIP identity, erased descriptors and name paths. */
public final class SymbolIdentity {
    private final JavacTask task;
    private final Elements elements;
    private final Types types;
    private final Trees trees;
    private final String defaultGav,jdkVersion;
    private final Function<String,String> coordinates;
    private final List<java.nio.file.Path> sources;
    private final Map<String,String> sourceLocations=new HashMap<>();
    private final Map<Element,String> scips=new IdentityHashMap<>(),namePaths=new IdentityHashMap<>();
    private final Map<Element,String> gavs=new IdentityHashMap<>();
    private final Map<Element,SemanticDeclaration> declarations=new IdentityHashMap<>();
    private final Map<Element,com.sun.source.util.TreePath> paths=new IdentityHashMap<>();
    public com.sun.source.util.TreePath path(Element element){if(!paths.containsKey(element))paths.put(element,trees.getPath(element));return paths.get(element);}
    public void remember(Element element,com.sun.source.util.TreePath path){if(element!=null)paths.put(element,path);}
    public SymbolIdentity(JavacTask task,String defaultGav,String jdkVersion,Function<String,String> coordinates){this(task,defaultGav,jdkVersion,coordinates,List.of());}
    public SymbolIdentity(JavacTask task,String defaultGav,String jdkVersion,Function<String,String> coordinates,List<java.nio.file.Path> sources){this.task=Objects.requireNonNull(task);this.sources=List.copyOf(sources);elements=task.getElements();types=task.getTypes();trees=Trees.instance(task);this.defaultGav=defaultGav;this.jdkVersion=jdkVersion;this.coordinates=coordinates;}
    public SemanticDeclaration declaration(Element element){return declaration(element,null);}
    public SemanticDeclaration declaration(Element element,SemanticFact reusable){
        var value=declarations.get(element);
        if(value==null){value=SemanticDeclaration.extract(task,this,element,reusable);declarations.put(element,value);}
        return value;
    }
    public String descriptor(TypeMirror type){
        type=types.erasure(type);
        return switch(type.getKind()){
            case BOOLEAN->"Z";case BYTE->"B";case SHORT->"S";case INT->"I";case LONG->"J";case CHAR->"C";case FLOAT->"F";case DOUBLE->"D";case VOID->"V";
            case ARRAY->"["+descriptor(((ArrayType)type).getComponentType());
            case DECLARED->"L"+elements.getBinaryName((TypeElement)((DeclaredType)type).asElement()).toString().replace('.','/')+";";
            case ERROR->throw new IllegalArgumentException("Unresolved type: "+type);
            default->"Ljava/lang/Object;";
        };
    }
    public String descriptor(ExecutableElement method){var value=new StringBuilder("(");for(var p:method.getParameters())value.append(descriptor(p.asType()));return value.append(')').append(descriptor(method.getReturnType())).toString();}
    public TypeElement declaring(Element element){while(element!=null&&!(element instanceof TypeElement))element=element.getEnclosingElement();return (TypeElement)element;}
    public String binaryName(TypeElement type){return elements.getBinaryName(type).toString();}
    public String gav(Element element){Element owner=declaring(element);if(owner==null)owner=element;if(gavs.containsKey(owner))return gavs.get(owner);String value=resolveGav(element);gavs.put(owner,value);return value;}
    private String resolveGav(Element element){
        TypeElement type=declaring(element);
        if(type instanceof ClassSymbol symbol){
            var path=path(type);
            if(path!=null){String found=coordinates.apply(path.getCompilationUnit().getSourceFile().toUri().toString());if(found!=null)return found;}
            if(symbol.classfile!=null){String found=coordinates.apply(symbol.classfile.getName());if(found!=null)return found;}
        }
        var module=elements.getModuleOf(element);
        if(module!=null&&!module.isUnnamed()&&(module.getQualifiedName().toString().startsWith("java.")||module.getQualifiedName().toString().startsWith("jdk.")))return "jdk:"+module.getQualifiedName()+":"+jdkVersion;
        return defaultGav;
    }
    public String sourceFile(Element element){
        var declaring=declaring(element);if(declaring==null)return null;String sourceKey=binaryName(declaring);if(sourceLocations.containsKey(sourceKey)){String prior=sourceLocations.get(sourceKey);return prior.isEmpty()?null:prior;}

        // Source roots are maintained input state and are more stable than javac's focused TreePath /
        // ClassSymbol.sourcefile metadata. Resolve the conventional top-level source location first.
        String pkg=elements.getPackageOf(declaring).getQualifiedName().toString().replace('.','/');
        String binarySimple=sourceKey.substring(sourceKey.lastIndexOf('.')+1),topLevel=binarySimple.split("\\$",2)[0]+".java";
        String relative=(pkg.isEmpty()?"":pkg+"/")+topLevel;
        for(var root:sources){
            var candidate=root.resolve(relative);
            if(java.nio.file.Files.isRegularFile(candidate)){
                String found=candidate.toAbsolutePath().normalize().toString();sourceLocations.put(sourceKey,found);return found;
            }
        }

        var path=path(element);if(path==null)path=path(declaring);
        java.net.URI uri=null;
        // Fall back to javac provenance for unsaved sources and legal secondary top-level declarations.
        if(declaring instanceof ClassSymbol symbol&&symbol.sourcefile!=null)uri=symbol.sourcefile.toUri();
        if(uri==null&&path!=null)uri=path.getCompilationUnit().getSourceFile().toUri();
        if(uri!=null&&"file".equals(uri.getScheme())&&uri.getPath().endsWith(".java")){
            var file=java.nio.file.Path.of(uri).toAbsolutePath().normalize();
            boolean knownSource=java.nio.file.Files.isRegularFile(file)||sources.stream().anyMatch(root->file.startsWith(root.toAbsolutePath().normalize()));
            if(knownSource){String found=file.toString();sourceLocations.put(sourceKey,found);return found;}
        }

        String filename=declaring instanceof ClassSymbol symbol&&symbol.sourcefile!=null?symbol.sourcefile.getName():topLevel;
        filename=filename.substring(filename.lastIndexOf('/')+1);
        String fallbackRelative=(pkg.isEmpty()?"":pkg+"/")+filename;
        for(var root:sources){var file=root.resolve(fallbackRelative);if(java.nio.file.Files.isRegularFile(file)){String found=file.toAbsolutePath().normalize().toString();sourceLocations.put(sourceKey,found);return found;}}
        sourceLocations.put(sourceKey,"");return null;
    }
    public String displayName(Element e){return e.getKind()==ElementKind.CONSTRUCTOR?e.getEnclosingElement().getSimpleName().toString():e.getSimpleName().toString();}
    public String namePath(Element e){String value=namePaths.get(e);if(value==null){value=resolveNamePath(e);namePaths.put(e,value);}return value;}
    private String resolveNamePath(Element e){
        if(e instanceof TypeElement type)return binaryName(type).replace('$','/');
        if(e instanceof PackageElement pkg)return pkg.getQualifiedName().toString();
        if(e instanceof ModuleElement module)return module.getQualifiedName().toString();
        if(e instanceof ExecutableElement method)return namePath(method.getEnclosingElement())+"/"+displayName(method)+"("+String.join(",",method.getParameters().stream().map(p->java.lang.constant.ClassDesc.ofDescriptor(descriptor(p.asType())).displayName().replace('$','.')).toList())+")";
        Element parent=e.getEnclosingElement();return (parent==null?"":namePath(parent)+"/")+displayName(e);
    }
    public String qualifiedNamePath(Element element){
        if(element instanceof TypeElement||element instanceof PackageElement||element instanceof ModuleElement)return namePath(element);
        if(element instanceof ExecutableElement method)return qualifiedNamePath(method.getEnclosingElement())+"/"+displayName(method)+"("+String.join(",",method.getParameters().stream().map(p->qualifiedErased(p.asType())).toList())+")";
        Element parent=element.getEnclosingElement();return (parent==null?"":qualifiedNamePath(parent)+"/")+displayName(element);
    }
    public String scip(Element e){String value=scips.get(e);if(value==null){value=resolveScip(e);scips.put(e,value);}return value;}
    private String resolveScip(Element e){
        if(Set.of(ElementKind.LOCAL_VARIABLE,ElementKind.RESOURCE_VARIABLE,ElementKind.EXCEPTION_PARAMETER,ElementKind.BINDING_VARIABLE).contains(e.getKind())){
            var path=path(e);String file=path==null?namePath(e):path.getCompilationUnit().getSourceFile().toUri().toString();long start=path==null?0:trees.getSourcePositions().getStartPosition(path.getCompilationUnit(),path.getLeaf());
            return "local "+dev.jvmd.core.Hashing.sha256(file.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,12)+"_"+start+"_"+displayName(e);
        }
        String[] coordinate=gav(e).split(":",3);return "maven "+coordinate[0]+"/"+coordinate[1]+" "+coordinate[2]+" "+descriptorPath(e);
    }
    private String descriptorPath(Element e){
        if(e instanceof PackageElement pkg)return pkg.getQualifiedName().toString().replace('.','/')+"/";
        if(e instanceof ModuleElement module)return module.getQualifiedName()+"/";
        if(e instanceof TypeElement type)return binaryName(type).replace('.','/').replace('$','#')+"#";
        if(e instanceof ExecutableElement method)return descriptorPath(e.getEnclosingElement())+method.getSimpleName()+"("+String.join(",",method.getParameters().stream().map(p->qualifiedErased(p.asType())).toList())+").";
        if(e.getKind()==ElementKind.TYPE_PARAMETER)return descriptorPath(e.getEnclosingElement())+"["+e.getSimpleName()+"]";
        if(e.getKind()==ElementKind.PARAMETER)return descriptorPath(e.getEnclosingElement())+"("+e.getSimpleName()+")";
        return descriptorPath(e.getEnclosingElement())+e.getSimpleName()+".";
    }
    private String qualifiedErased(TypeMirror type){return dev.jvmd.index.Signatures.qualified(java.lang.constant.ClassDesc.ofDescriptor(descriptor(type)));}
    public String signature(Element e){
        String modifiers=String.join(" ",e.getModifiers().stream().map(Object::toString).sorted().toList());if(!modifiers.isEmpty())modifiers+=" ";
        if(e instanceof ExecutableElement m){String generics=typeParameters(m.getTypeParameters());if(!generics.isEmpty())generics+=" ";return modifiers+generics+(m.getKind()==ElementKind.CONSTRUCTOR?"":m.getReturnType()+" ")+displayName(m)+"("+String.join(", ",m.getParameters().stream().map(p->p.asType()+" "+p.getSimpleName()).toList())+")"+(m.getThrownTypes().isEmpty()?"":" throws "+String.join(", ",m.getThrownTypes().stream().map(Object::toString).toList()));}
        if(e instanceof TypeElement type){String s=modifiers+kind(type)+" "+type.getQualifiedName()+typeParameters(type.getTypeParameters());if(type.getSuperclass().getKind()!=TypeKind.NONE&&!type.getSuperclass().toString().equals("java.lang.Object"))s+=" extends "+type.getSuperclass();if(!type.getInterfaces().isEmpty())s+=(type.getKind()==ElementKind.INTERFACE?" extends ":" implements ")+String.join(", ",type.getInterfaces().stream().map(Object::toString).toList());return s;}
        if(e instanceof PackageElement pkg)return "package "+pkg.getQualifiedName();
        if(e instanceof ModuleElement module)return "module "+module.getQualifiedName();
        return modifiers+e.asType()+" "+e.getSimpleName();
    }
    private static String typeParameters(List<? extends TypeParameterElement> values){if(values.isEmpty())return "";return "<"+String.join(", ",values.stream().map(p->p.getSimpleName()+(p.getBounds().size()==1&&p.getBounds().getFirst().toString().equals("java.lang.Object")?"":" extends "+String.join(" & ",p.getBounds().stream().map(Object::toString).toList()))).toList())+">";}
    public static String kind(Element e){return switch(e.getKind()){case CONSTRUCTOR->"ctor";case ANNOTATION_TYPE->"annotation";case ENUM_CONSTANT->"enumconst";default->e.getKind().name().toLowerCase(Locale.ROOT);};}
}
