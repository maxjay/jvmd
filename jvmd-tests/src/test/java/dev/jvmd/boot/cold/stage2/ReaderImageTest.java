package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.machine.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReaderImageTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private byte[] binary(Attribute<?> attribute) {
        return ClassFile.of().build(ClassDesc.of("q.Hidden"),b->{
            b.withFlags(ClassFile.ACC_PUBLIC);
            b.withField("value",ConstantDescs.CD_int,f->f.withFlags(ClassFile.ACC_PRIVATE).with((FieldElement)attribute));
        });
    }
    @Test void privateReaderFailureDoesNotChangeTheParserSixFactFilter() throws Exception {
        // A local-variable target is deliberately misplaced on a private field. It is not a supported reader recipe.
        var annotation=TypeAnnotation.of(TypeAnnotation.TargetInfo.ofLocalVariable(List.of()),List.of(),Annotation.of(ClassDesc.of("q.A")));
        var facts=ClassFacts.of(Sha256.INSTANCE,binary(RuntimeVisibleTypeAnnotationsAttribute.of(annotation)),"q/Hidden");
        assertThat(facts.facts()).hasSize(1);assertThat(facts.reader().supported()).isFalse();
    }
    @Test void privateAnnotationOrderIsAnOrderedRecipeIndependentOfTAndA() throws Exception {
        var a=Annotation.of(ClassDesc.of("q.A"));var b=Annotation.of(ClassDesc.of("q.B"));
        var x=ClassFacts.of(Sha256.INSTANCE,binary(RuntimeVisibleAnnotationsAttribute.of(a,b)),"q/Hidden");
        var y=ClassFacts.of(Sha256.INSTANCE,binary(RuntimeVisibleAnnotationsAttribute.of(b,a)),"q/Hidden");
        assertThat(x.facts().stream().map(Fact::h).toList()).isEqualTo(y.facts().stream().map(Fact::h).toList());
        var tree=new ContentTree(Sha256.INSTANCE);var store=new InMemoryLocalStore();
        var first=ReaderImage.seal(tree,store,x.reader());var second=ReaderImage.seal(tree,store,y.reader());
        assertThat(first.h()).isNotEqualTo(second.h());assertThat(x.reader().supported()).isTrue();assertThat(y.reader().supported()).isTrue();
    }
    @Test void parameterNamesAreASeparateRecipeAndBodyInstructionsAreExcluded() throws Exception {
        var x=Stage2Support.compile(directory.resolve("x"),Map.of("q/C.java","package q; public class C { public int f(int oldName){return oldName+1;} }"),List.of("-g"),List.of()).get("q/C.class");
        var y=Stage2Support.compile(directory.resolve("y"),Map.of("q/C.java","package q; public class C { public int f(int newName){return newName+2;} }"),List.of("-g"),List.of()).get("q/C.class");
        var a=ClassFacts.of(Sha256.INSTANCE,x,"q/C").reader();var b=ClassFacts.of(Sha256.INSTANCE,y,"q/C").reader();
        assertThat(a.recipe()).usingRecursiveComparison().isEqualTo(b.recipe());
        assertThat(a.parameterRecipe()).usingRecursiveComparison().isNotEqualTo(b.parameterRecipe());
        var z=Stage2Support.compile(directory.resolve("z"),Map.of("q/C.java","package q; public class C { public int f(int oldName){return oldName+200;} }"),List.of("-g"),List.of()).get("q/C.class");
        assertThat(a.parameterRecipe()).usingRecursiveComparison().isEqualTo(ClassFacts.of(Sha256.INSTANCE,z,"q/C").reader().parameterRecipe());
    }
    @Test void sourceFileAndSyntheticAttributeAreRetainedAtTheirReaderProjection() throws Exception {
        var cf=ClassFile.of();byte[] original=binary(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("q.A"))));
        byte[] source=cf.transformClass(cf.parse(original),new ClassTransform() {
            @Override public void accept(ClassBuilder out,ClassElement e){out.with(e);}
            @Override public void atEnd(ClassBuilder out){out.with(SourceFileAttribute.of("Other.java"));}
        });
        var before=ClassFacts.of(Sha256.INSTANCE,original,"q/Hidden");var after=ClassFacts.of(Sha256.INSTANCE,source,"q/Hidden");
        assertThat(after.facts().stream().map(Fact::h).toList()).isEqualTo(before.facts().stream().map(Fact::h).toList());
        assertThat(after.reader().recipe()).usingRecursiveComparison().isNotEqualTo(before.reader().recipe());
        byte[] synthetic=cf.transformClass(cf.parse(original),ClassTransform.transformingFields(new FieldTransform() {
            @Override public void accept(FieldBuilder out,FieldElement e){out.with(e);}
            @Override public void atEnd(FieldBuilder out){out.with(SyntheticAttribute.of());}
        }));
        assertThat(before.reader().names()).anyMatch(n->n.operation()==ReaderImage.VARIABLE && n.name().equals("value"));
        assertThat(ClassFacts.of(Sha256.INSTANCE,synthetic,"q/Hidden").reader().names()).noneMatch(n->n.operation()==ReaderImage.VARIABLE && n.name().equals("value"));
    }
}
