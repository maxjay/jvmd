package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.index.FactCodec;
import dev.jvmd.core.NamePath;
import dev.jvmd.index.rocks.KeyedFacts;
import dev.jvmd.core.Hashing;
import org.rocksdb.*;
import java.lang.ref.Cleaner;
import java.nio.file.*;
import java.util.*;

/** Session-owned authoritative detached facts. Read handles pin database revisions, not decoded graphs. */
final class BindingFacts implements AutoCloseable {
    private static final Cleaner CLEANER=Cleaner.create();
    private static final Set<String> LOCALS=Set.of("local_variable","parameter","exception_parameter","binding_variable","resource_variable","type_parameter");
    private final Path root;
    private final Cache blocks=new LRUCache(4L*1024*1024);
    private final Options options=new Options().setCreateIfMissing(true).setWriteBufferSize(4L*1024*1024).setMaxWriteBufferNumber(2)
            .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(blocks));
    private final RocksDB db;
    private final KeyedFacts facts;
    private final Set<Lease> leases=new HashSet<>();
    private final LinkedHashMap<String,Cached> decoded=new LinkedHashMap<>(64,.75f,true);
    private record Cached(Object value,long weight) {}
    private long budget,bytes,reads,revision;
    static { RocksDB.loadLibrary(); }
    BindingFacts()throws Exception {root=Files.createTempDirectory("jvmd-bindings-");db=RocksDB.open(options,root.toString());facts=new KeyedFacts(db,"b/");}
    void budget(long requested){budget=Math.max(0,Math.min(16L*1024*1024,requested));trim();}
    private void trim(){while(bytes>budget&&!decoded.isEmpty()){var entry=decoded.pollFirstEntry();bytes-=entry.getValue().weight();}}
    WriteBatch transaction(){return new WriteBatch();}
    void commit(WriteBatch batch)throws Exception {try(var write=new WriteOptions().setDisableWAL(true)){db.write(write,batch);}revision++;}
    void remove(WriteBatch batch,Path file)throws Exception {facts.replace(batch,file.toString(),List.of());}
    void replace(WriteBatch batch,Path file,CompilerPool.Outcome<Bindings.Snapshot> outcome)throws Exception {
        var rows=new ArrayList<KeyedFacts.Fact>();var graph=outcome.result();
        if(graph!=null){
            String owner=Hashing.sha256(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var declarations=new HashSet<String>();
            for(var o:graph.occurrences())if(o.role().equals("declaration")&&Path.of(o.file()).toAbsolutePath().normalize().equals(file))declarations.add(o.scip());
            int index=0;
            for(var entry:graph.symbols().entrySet()){
                var symbol=entry.getValue();boolean declaration=declarations.contains(entry.getKey())&&!LOCALS.contains(symbol.get("kind"));
                var postings=new LinkedHashSet<String>();postings.add("symbols");postings.add("symbol/"+KeyedFacts.part(entry.getKey()));
                if(declaration){postings.add("declarations");postings.add("declaration/"+KeyedFacts.part(entry.getKey()));}
                for(String field:List.of("name_path","qualified_name_path","name","fqn")){
                    String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())postings.add("name/"+KeyedFacts.part(value));
                }
                postings.add("leaf/"+KeyedFacts.part(Objects.toString(symbol.get("name"),"")));
                for(String field:List.of("name","name_path")){
                    String value=Objects.toString(symbol.get(field),"").toLowerCase(Locale.ROOT);
                    for(int width=1;width<=Math.min(3,value.length());width++)for(int offset=0;offset+width<=value.length();offset++)postings.add("gram/"+KeyedFacts.part(value.substring(offset,offset+width)));
                }
                rows.add(new KeyedFacts.Fact(owner+"/s/"+String.format(Locale.ROOT,"%08x",index++),FactCodec.encode(symbol),postings));
            }
            index=0;
            for(var edge:graph.edges())rows.add(new KeyedFacts.Fact(owner+"/e/"+String.format(Locale.ROOT,"%08x",index++),FactCodec.encode(edge),Set.of("edges","out/"+KeyedFacts.part(edge.src()),"in/"+KeyedFacts.part(edge.dst()))));
            index=0;
            for(var o:graph.occurrences()){
                var postings=new LinkedHashSet<String>();postings.add("occurrences");postings.add("occurrence/"+KeyedFacts.part(o.scip()));
                if(o.container()!=null)postings.add("reference/"+edgeKey(new Bindings.Edge(o.container(),o.scip(),o.role())));
                rows.add(new KeyedFacts.Fact(owner+"/o/"+String.format(Locale.ROOT,"%08x",index++),FactCodec.encode(o),postings));
            }
        }
        facts.replace(batch,file.toString(),rows);
    }
    private static String edgeKey(Bindings.Edge edge){return KeyedFacts.part(edge.src())+"/"+KeyedFacts.part(edge.dst())+"/"+KeyedFacts.part(edge.kind());}
    Map<String,Object> status(){return Map.of("decoded_budget_bytes",budget,"decoded_estimated_bytes",bytes,"fact_decodes",reads,"fact_revision",revision);}
    synchronized View view(){var lease=new Lease(db.getSnapshot());leases.add(lease);return new View(lease,revision);}
    private final class Lease implements Runnable {
        private final org.rocksdb.Snapshot snapshot;
        private final ReadOptions read;
        private boolean closed;
        Lease(org.rocksdb.Snapshot snapshot){this.snapshot=snapshot;read=new ReadOptions().setSnapshot(snapshot);}
        @Override public void run(){synchronized(BindingFacts.this){if(!closed){closed=true;read.close();db.releaseSnapshot(snapshot);leases.remove(this);}}}
    }
    final class View implements AutoCloseable {
        private final Lease lease;
        private final long version;
        private final Cleaner.Cleanable cleanup;
        View(Lease lease,long version){this.lease=lease;this.version=version;cleanup=CLEANER.register(this,lease);}
        private <T> T decode(String id,byte[] value,Class<T> type)throws Exception {
            String key=version+"/"+id;var cached=decoded.get(key);Object object;
            if(cached!=null)object=cached.value();else{
                object=FactCodec.decode(value,type);reads++;long weight=128+value.length*4L;
                if(weight<=budget){decoded.put(key,new Cached(object,weight));bytes+=weight;trim();}
            }return type.cast(object);
        }
        private void check(){if(lease.closed)throw new IllegalStateException("Binding read view is closed");}
        private <T> List<T> select(String posting,Class<T> type)throws Exception {
            synchronized(BindingFacts.this){check();var result=new ArrayList<T>();facts.select(lease.read,posting,false,(id,value)->{result.add(decode(id,value,type));return true;});return List.copyOf(result);}
        }
        boolean declares(String scip)throws Exception {
            synchronized(BindingFacts.this){check();boolean[] found={false};facts.selectKeys(lease.read,"declaration/"+KeyedFacts.part(scip),false,id->{found[0]=true;return false;});return found[0];}
        }
        private String primary(String scip)throws Exception {
            String[] result={null};facts.selectKeys(lease.read,"declaration/"+KeyedFacts.part(scip),false,id->{result[0]=id;return false;});
            if(result[0]==null)facts.selectKeys(lease.read,"symbol/"+KeyedFacts.part(scip),false,id->{result[0]=id;return true;});return result[0];
        }
        @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(String posting)throws Exception {return (List)select(posting,Map.class);}
        @SuppressWarnings("unchecked") Map<String,Object> symbol(String scip)throws Exception {
            synchronized(BindingFacts.this){check();String id=primary(scip);return id==null?null:(Map<String,Object>)decode(id,facts.get(lease.read,id),Map.class);}
        }
        Map<String,Map<String,Object>> symbols(boolean declarations)throws Exception {
            var result=new LinkedHashMap<String,Map<String,Object>>();for(var row:rows(declarations?"declarations":"symbols"))result.put(row.get("scip").toString(),row);
            if(!declarations)for(var row:rows("declarations"))result.put(row.get("scip").toString(),row);return Collections.unmodifiableMap(result);
        }
        List<Map<String,Object>> lookup(String ref)throws Exception {
            var result=new LinkedHashMap<String,Map<String,Object>>();for(var row:rows("name/"+KeyedFacts.part(ref))){String scip=row.get("scip").toString();result.put(scip,symbol(scip));}return List.copyOf(result.values());
        }
        dev.jvmd.index.SymbolReadView.Page find(String query,boolean substring,Set<String> kinds,int limit,String cursor,java.util.function.Predicate<Map<String,Object>> filter)throws Exception {
            synchronized(BindingFacts.this){check();String posting;
                if(substring){String lower=query.toLowerCase(Locale.ROOT);posting=lower.isEmpty()?"symbols":"gram/"+KeyedFacts.part(lower.substring(0,Math.min(3,lower.length())));}
                else {var path=NamePath.parse(query);posting=path.identity()?"symbol/"+KeyedFacts.part(query):"leaf/"+KeyedFacts.part(path.leaf());}
                var result=new ArrayList<Map<String,Object>>();String[] last={null};boolean[] more={false};
                facts.select(lease.read,posting,false,(id,value)->{
                    if(cursor!=null&&id.compareTo(cursor)<=0)return true;
                    @SuppressWarnings("unchecked") var row=(Map<String,Object>)decode(id,value,Map.class);
                    if((!kinds.isEmpty()&&!kinds.contains(row.get("kind")))||!Analyzer.matches(row,query,substring)||!filter.test(row))return true;
                    String scip=row.get("scip").toString();
                    if(!id.equals(primary(scip))||(!query.contains(")/")&&!facts.contains(lease.read,"declaration/"+KeyedFacts.part(scip))))return true;
                    if(result.size()==limit){more[0]=true;return false;}result.add(row);last[0]=id;return true;
                });return new dev.jvmd.index.SymbolReadView.Page(result,more[0]?last[0]:null);
            }
        }
        List<Bindings.Edge> edges()throws Exception {return select("edges",Bindings.Edge.class).stream().distinct().toList();}
        List<Bindings.Occurrence> occurrences()throws Exception {return select("occurrences",Bindings.Occurrence.class);}
        List<Bindings.Occurrence> occurrences(String scip)throws Exception {return select("occurrence/"+KeyedFacts.part(scip),Bindings.Occurrence.class);}
        List<Bindings.Edge> adjacent(Set<String> frontier,boolean forward)throws Exception {
            var result=new LinkedHashSet<Bindings.Edge>();for(String scip:frontier)result.addAll(select((forward?"out/":"in/")+KeyedFacts.part(scip),Bindings.Edge.class));return List.copyOf(result);
        }
        List<Bindings.Occurrence> references(Set<Bindings.Edge> edges)throws Exception {
            var result=new LinkedHashSet<Bindings.Occurrence>();for(var edge:edges)result.addAll(select("reference/"+edgeKey(edge),Bindings.Occurrence.class));return List.copyOf(result);
        }
        @Override public void close(){cleanup.clean();}
    }
    @Override public synchronized void close()throws Exception {
        for(var lease:List.copyOf(leases))lease.run();decoded.clear();bytes=0;db.close();options.close();blocks.close();
        try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
    }
}
