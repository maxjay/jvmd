package dev.jvmd.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Implements 4.8 and 4.9: checked UTF-16 edit plans, atomic file replacement and rollback. */
public final class TextEdits {
    /** Implements 4.8: a half-open edit over the original document. */
    public record Edit(Path file,int start,int end,String text) { }
    private record Change(Path file,Path target,String before,String after,List<Edit> edits) { }
    /** Implements 4.9: a detached edit plan which can be returned without changing files. */
    public static final class Plan {
        private final List<Change> changes;
        private Plan(List<Change> changes){this.changes=List.copyOf(changes);}
        public List<Path> files(){return changes.stream().map(Change::target).toList();}
        public String content(Path path){return changes.stream().filter(c->c.target().equals(path)).findFirst().orElseThrow().after();}
        public List<Map<String,Object>> edits(){
            var result=new ArrayList<Map<String,Object>>();
            for(var change:changes){var edits=new ArrayList<Map<String,Object>>();
                for(var edit:change.edits())edits.add(Map.of("start",edit.start(),"end",edit.end(),"range",Map.of("start",position(change.before(),edit.start()),"end",position(change.before(),edit.end())),"new_text",edit.text()));
                var file=new LinkedHashMap<String,Object>();file.put("path",change.file().toString());file.put("sha256",hash(change.before()));file.put("new_path",change.file().equals(change.target())?null:change.target().toString());file.put("text_edits",edits);result.add(file);
            }return List.copyOf(result);
        }
        public List<int[]> touched(Path file){
            var result=new ArrayList<int[]>();for(var change:changes)if(change.target().equals(file)){
                int delta=0;for(var edit:change.edits()){int start=edit.start()+delta;result.add(new int[]{start,start+edit.text().length()});delta+=edit.text().length()-(edit.end()-edit.start());}
            }return result;
        }
    }
    private TextEdits() { }
    private static String hash(String text){return Hashing.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Integer> position(String text,int offset){
        int line=0,start=0;for(int i=0;i<offset;i++)if(text.charAt(i)=='\n'){line++;start=i+1;}return Map.of("line",line,"character",offset-start);
    }
    public static Plan prepare(List<Edit> edits,Map<Path,Path> renames)throws Exception{return prepare(edits,renames,Map.of());}
    public static Plan prepare(List<Edit> edits,Map<Path,Path> renames,Map<Path,String> documents)throws Exception{
        if(edits.size()>100000)throw RpcException.invalid("An edit plan may contain at most 100000 ranges");
        var grouped=new LinkedHashMap<Path,List<Edit>>();
        for(var edit:edits){Path file=edit.file().toAbsolutePath().normalize();grouped.computeIfAbsent(file,_->new ArrayList<>()).add(new Edit(file,edit.start(),edit.end(),Objects.requireNonNull(edit.text())));}
        for(Path file:renames.keySet())grouped.computeIfAbsent(file.toAbsolutePath().normalize(),_->new ArrayList<>());
        var changes=new ArrayList<Change>();var targets=new HashSet<Path>();
        for(var entry:grouped.entrySet()){
            Path file=entry.getKey(),target=renames.getOrDefault(file,file).toAbsolutePath().normalize();
            if(!Files.isRegularFile(file)&&!documents.containsKey(file))throw RpcException.invalid("Source file does not exist: "+file);
            if(!target.equals(file)&&(Files.exists(target)||grouped.containsKey(target)))throw RpcException.invalid("Rename target already exists: "+target);
            if(!targets.add(target))throw RpcException.invalid("Multiple files share an edit target: "+target);
            String before=documents.containsKey(file)?documents.get(file):Files.readString(file);var ranges=entry.getValue();ranges.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
            int end=-1,lastStart=-1;for(var edit:ranges){
                if(edit.start()<0||edit.end()<edit.start()||edit.end()>before.length()||edit.start()<end||edit.start()==lastStart)throw RpcException.invalid("Overlapping or invalid edit range in "+file);
                if(splitSurrogate(before,edit.start())||splitSurrogate(before,edit.end()))throw RpcException.invalid("Edit splits a UTF-16 surrogate pair");
                end=edit.end();lastStart=edit.start();
            }
            var after=new StringBuilder(before);for(var edit:ranges.reversed())after.replace(edit.start(),edit.end(),edit.text());
            changes.add(new Change(file,target,before,after.toString(),List.copyOf(ranges)));
        }return new Plan(changes);
    }
    private static boolean splitSurrogate(String text,int offset){return offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset));}
    public static synchronized void apply(Plan plan)throws Exception{
        var staged=new LinkedHashMap<Change,Path>();var applied=new ArrayList<Change>();
        try{
            for(var change:plan.changes){
                unchanged(change);
                if(!change.file().equals(change.target())&&Files.exists(change.target()))throw RpcException.invalid("Rename target already exists: "+change.target());
                Path temporary=Files.createTempFile(change.target().getParent(),".jvmd-edit-",".java");staged.put(change,temporary);Files.writeString(temporary,change.after());
                Files.setPosixFilePermissions(temporary,Files.getPosixFilePermissions(change.file()));
            }
            for(var change:plan.changes)unchanged(change);
            for(var change:plan.changes){
                unchanged(change);
                if(change.file().equals(change.target()))Files.move(staged.get(change),change.target(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                else Files.createLink(change.target(),staged.get(change)); // Atomic create-if-absent: a concurrent destination is never overwritten.
                applied.add(change);
                if(!change.file().equals(change.target()))Files.delete(change.file());
            }
        }catch(Exception failure){
            for(var change:applied.reversed())try{
                if(!Files.readString(change.target()).equals(change.after()))throw new java.io.IOException("Concurrent change prevents rollback: "+change.target());
                Path backup=Files.createTempFile(change.file().getParent(),".jvmd-rollback-",".java");
                try{Files.writeString(backup,change.before());Files.setPosixFilePermissions(backup,Files.getPosixFilePermissions(change.target()));if(change.file().equals(change.target()))Files.move(backup,change.file(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else if(Files.exists(change.file())){if(!Files.readString(change.file()).equals(change.before()))throw new java.io.IOException("Concurrent source prevents rollback: "+change.file());}else Files.createLink(change.file(),backup);}finally{Files.deleteIfExists(backup);}
                if(!change.target().equals(change.file()))Files.delete(change.target());
            }catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }finally{for(Path temporary:staged.values())Files.deleteIfExists(temporary);}
    }
    private static void unchanged(Change change)throws Exception{
        if(!Files.isRegularFile(change.file())||!Files.readString(change.file()).equals(change.before()))throw RpcException.invalid("Source changed since edit planning: "+change.file());
    }
}
