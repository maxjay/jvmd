package dev.jvmd.index;
import dev.jvmd.core.JavaTypes;
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.util.*;
/** Implements 4.4: compatibility facade over the shared public class-file signature renderer. */
public final class Signatures {
    private Signatures() { }
    public static String type(Signature signature){return JavaTypes.type(signature);}
    public static String parameters(List<Signature.TypeParam> parameters){return JavaTypes.parameters(parameters);}
    public static String qualified(ClassDesc type){return JavaTypes.qualified(type);}
    public static Set<String> referenced(Signature signature){return JavaTypes.referenced(signature);}
}
