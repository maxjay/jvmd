import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import java.util.*;
public class MigrationCheck {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),a=root.resolve("A"),b=root.resolve("B"),u=root.resolve("U");
  try(var store=new RocksSemanticInvalidation(root.resolve("state"))){
   if(args[1].equals("seed")){
    store.observeFile("m","ctx",new FileSemanticContribution(a,"1","a",Set.of(),Set.of("p.A"),Set.of()));
    store.observeFile("m","ctx",new FileSemanticContribution(b,"1","b",Set.of(a),Set.of("p.B"),Set.of()));
    store.observeFile("m","ctx",new FileSemanticContribution(u,"1","u",Set.of(),Set.of("p.U"),Set.of("p.A.Missing")));
   }else {
    var result=store.observeFile("m","ctx",new FileSemanticContribution(a,"2","a2",Set.of(),Set.of("p.A"),Set.of()));
    if(!result.reanalyze().equals(Set.of(a,b,u)))throw new AssertionError(result);
    if(store.revision(b)!=1||store.revision(u)!=1)throw new AssertionError("Missing invalidation revision");
    System.out.println("Legacy contribution migration: affected-file and revision checks passed");
   }
  }
 }
}
