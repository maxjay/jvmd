package dev.jvmd.core;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Equivalence classes are the identity contract: two part-lists that were equal under a legacy
 * SHA-256 encoder must be equal under the next encoder, and unequal ones must stay unequal. Each
 * legacy encoder is checked only over the inputs it handled deterministically.
 */
@Tag("phase-1")
class IdentityEquivalenceTest {
    private static final int SAMPLES=10_000;
    private static final Object[] SCALARS={"",null,"5",5,5L,(short)5,(byte)5,"-7",-7L,-7,"a","ab","é","日本","😀",
            Boolean.TRUE,"true",false,"false",Path.of("a/b"),"a/b",Thread.State.NEW,"NEW",Long.MIN_VALUE,String.valueOf(Long.MIN_VALUE),
            Integer.MAX_VALUE,"2147483647",'x',"x","0",0,0L};
    private static final String[] DOMAINS={"d","5",""};

    /** Placeholder for an identity value; each side materializes it into its own identity type. */
    private record Ident(int n) { }
    private enum Profile { CANONICAL, LIVE_STATE, COMPILER_INPUTS }

    @Test void canonicalDigestWriterClassesArePreserved(){
        check(Profile.CANONICAL,parts->LegacyEncoders.canonical((String)parts[0],rest(materialize(parts,IdentityEquivalenceTest::legacyIdentity))),
                parts->next(Profile.CANONICAL,(String)parts[0],rest(materialize(parts,IdentityEquivalenceTest::nextIdentity))));
    }
    @Test void liveStateTreeClassesArePreserved(){
        check(Profile.LIVE_STATE,parts->LegacyEncoders.liveState((String)parts[0],rest(parts)),parts->next(Profile.LIVE_STATE,(String)parts[0],rest(parts)));
    }
    @Test void compilerInputsClassesArePreserved(){
        check(Profile.COMPILER_INPUTS,parts->LegacyEncoders.compilerInputs((String)parts[0],rest(parts)),parts->next(Profile.COMPILER_INPUTS,(String)parts[0],rest(parts)));
    }

    /** The encoder under test: one encoder replaces all three, with identical arguments. */
    private static Object next(Profile profile,String domain,Object... parts){return IdentityEncoder.of(domain,parts);}
    private static Object legacyIdentity(Ident ident){return new Id128(0x5EED+ident.n(),~ident.n());}
    private static Object nextIdentity(Ident ident){return legacyIdentity(ident);}

    @Test void typedBuilderMatchesVarargsOnTheOracleCorpus(){
        var random=new Random(7);
        for(int i=0;i<SAMPLES;i++){
            String domain=DOMAINS[random.nextInt(DOMAINS.length)];
            Object scalar=SCALARS[random.nextInt(SCALARS.length)];
            long number=random.nextInt(3)==0?Long.MIN_VALUE+random.nextInt(3):random.nextLong(-1000,1000);
            var identity=new Id128(random.nextLong(),random.nextLong());
            byte[] bytes=new byte[random.nextInt(4)];random.nextBytes(bytes);
            boolean flag=random.nextBoolean();
            String text=scalar==null?null:scalar.toString();
            var typed=IdentityEncoder.begin(domain).str(text).num(number).id(identity).bytes(bytes).bool(flag)
                    .seq(2).str("a").id(null).finish();
            var varargs=IdentityEncoder.of(domain,scalar,number,identity,bytes,flag,List.of("a",""));
            assertThat(typed).as("sample %d",i).isEqualTo(varargs);
            assertThat(IdentityEncoder.begin(domain).num(number).finish()).isEqualTo(IdentityEncoder.of(domain,Long.toString(number)));
        }
    }

    @Test void identityPartsNoLongerEqualTheirHexText(){
        // The one intentional divergence: LiveStateTree/CompilerInputs used to hash an identity as its hex text.
        var identity=new Id128(1,2);
        assertThat(IdentityEncoder.of("d",identity)).isNotEqualTo(IdentityEncoder.of("d",identity.hex()));
    }

    @Test void determinismAssertionsRejectUnorderedInputs(){
        assertThatThrownBy(()->IdentityEncoder.of("d",new HashSet<>(List.of("a","b")))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->IdentityEncoder.of("d",new HashMap<>(Map.of("a","b")))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->IdentityEncoder.of("d",(Object)new int[]{1})).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->IdentityEncoder.of("d",new Ident(1))).isInstanceOf(AssertionError.class);
        assertThatCode(()->IdentityEncoder.of("d",new TreeSet<>(List.of("a")),new LinkedHashMap<>(Map.of("a","b")))).doesNotThrowAnyException();
    }

    private static void check(Profile profile,Function<Object[],Object> legacy,Function<Object[],Object> next){
        var random=new Random(20261001L+profile.ordinal());
        var legacyToNext=new HashMap<Object,Object>();var nextToLegacy=new HashMap<Object,Object>();
        var inputs=new HashSet<String>();
        for(int i=0;i<SAMPLES;i++){
            int count=random.nextInt(4);
            Object[] parts=new Object[count+1];parts[0]=DOMAINS[random.nextInt(DOMAINS.length)];
            for(int p=1;p<=count;p++)parts[p]=value(random,profile,0);
            inputs.add(describe(parts));
            Object l=legacy.apply(parts),n=next.apply(parts);
            Object priorNext=legacyToNext.putIfAbsent(l,n),priorLegacy=nextToLegacy.putIfAbsent(n,l);
            assertThat(priorNext==null||priorNext.equals(n)).as("legacy-equal inputs diverged: %s",describe(parts)).isTrue();
            assertThat(priorLegacy==null||priorLegacy.equals(l)).as("legacy-distinct inputs collided: %s",describe(parts)).isTrue();
        }
        // The corpus must actually exercise merged classes (5 / 5L / "5", null / "").
        assertThat(legacyToNext.size()).isLessThan(inputs.size());
    }

    private static Object value(Random random,Profile profile,int depth){
        int choice=random.nextInt(depth>=3?1:profile==Profile.CANONICAL?6:profile==Profile.COMPILER_INPUTS?4:3);
        return switch(choice){
            case 0 -> SCALARS[random.nextInt(SCALARS.length)];
            case 1 -> {var list=new ArrayList<>();int n=random.nextInt(4);for(int i=0;i<n;i++)list.add(value(random,profile,depth+1));yield list;}
            case 2 -> {var set=new TreeSet<String>();int n=random.nextInt(3);for(int i=0;i<n;i++)set.add(String.valueOf(SCALARS[random.nextInt(4)+2]));yield set;}
            case 3 -> profile==Profile.COMPILER_INPUTS?map(random,profile,depth):array(random,profile,depth);
            case 4 -> new Ident(random.nextInt(3));
            default -> {byte[] bytes=new byte[random.nextInt(3)];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)random.nextInt(2);yield bytes;}
        };
    }
    private static Object[] array(Random random,Profile profile,int depth){
        Object[] values=new Object[random.nextInt(4)];for(int i=0;i<values.length;i++)values[i]=value(random,profile,depth+1);return values;
    }
    private static SortedMap<String,Object> map(Random random,Profile profile,int depth){
        var map=new TreeMap<String,Object>();int n=random.nextInt(3);
        for(int i=0;i<n;i++)map.put(new String[]{"","a","5"}[random.nextInt(3)],value(random,profile,depth+1));
        return map;
    }

    private static Object[] rest(Object[] parts){return Arrays.copyOfRange(parts,1,parts.length);}
    private static Object[] materialize(Object[] parts,Function<Ident,Object> identity){
        Object[] result=new Object[parts.length];for(int i=0;i<parts.length;i++)result[i]=materialize(parts[i],identity);return result;
    }
    private static Object materialize(Object value,Function<Ident,Object> identity){
        if(value instanceof Ident ident)return identity.apply(ident);
        if(value instanceof Object[] values)return materialize(values,identity);
        if(value instanceof List<?> values)return values.stream().map(item->materialize(item,identity)).toList();
        return value;
    }
    private static String describe(Object value){
        if(value==null)return "null";
        if(value instanceof Object[] values)return "arr"+Arrays.stream(values).map(IdentityEquivalenceTest::describe).toList();
        if(value instanceof byte[] bytes)return "bytes"+Arrays.toString(bytes);
        if(value instanceof Collection<?> values)return value.getClass().getSimpleName()+values.stream().map(IdentityEquivalenceTest::describe).toList();
        if(value instanceof Map<?,?> map)return "map"+map.entrySet().stream().map(e->describe(e.getKey())+"="+describe(e.getValue())).toList();
        return value.getClass().getSimpleName()+":"+value;
    }
}
