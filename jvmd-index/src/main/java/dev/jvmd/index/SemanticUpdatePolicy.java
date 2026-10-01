package dev.jvmd.index;

import dev.jvmd.core.Hash256;
import java.nio.file.Path;
import java.util.*;

/** Semantic decisions independent of storage and compiler ownership. Call under the owner's executor/transaction. */
public final class SemanticUpdatePolicy {
    private SemanticUpdatePolicy() {}
    public enum Completeness { COMPLETE, FOCUSED, FAILED }
    public enum EnvironmentTransition { NONE, PRECISE_CLASSPATH, UNKNOWN }
    public record Change(FileSemanticContribution before,FileSemanticContribution after) {
        public Change {
            if(before==null&&after==null)throw new IllegalArgumentException("Empty change");
            if(before!=null&&after!=null&&!before.file().equals(after.file()))throw new IllegalArgumentException("Different files");
        }
        public Path file(){return after==null?before.file():after.file();}
    }
    public record Result(Set<Path> reanalyze,Set<Path> apiChanged,Set<Path> bodyOnly,Set<Path> deleted,
                         boolean contextChanged,boolean namespaceChanged) {
        public Result { reanalyze=Set.copyOf(reanalyze);apiChanged=Set.copyOf(apiChanged);bodyOnly=Set.copyOf(bodyOnly);deleted=Set.copyOf(deleted); }
    }
    /** Derived postings of the published graph, read before replacing or deleting its contributions. */
    public interface Postings {
        Set<Path> dependants(Path file);
        Set<Path> unresolved(Set<String> exports);
        Set<Path> files();
    }
    public static Result decide(Collection<Change> changes,boolean environmentChanged,Postings postings){
        return decide(changes,environmentChanged?EnvironmentTransition.UNKNOWN:EnvironmentTransition.NONE,postings);
    }
    public static Result decide(Collection<Change> changes,EnvironmentTransition environment,Postings postings){
        return decide(changes,environment,postings,ignored->false);
    }
    private static Result decide(Collection<Change> changes,EnvironmentTransition environment,Postings postings,
                                 java.util.function.Predicate<Path> proofCovered){
        Objects.requireNonNull(environment);Objects.requireNonNull(postings);Objects.requireNonNull(proofCovered);
        try(var trace=dev.jvmd.core.RequestScope.stage("semantic.invalidate")){
            trace.count("changed_files",changes.size());
            trace.count("environment_unknown",environment==EnvironmentTransition.UNKNOWN?1:0);
            trace.count("environment_precise_classpath",environment==EnvironmentTransition.PRECISE_CLASSPATH?1:0);
        var api=new LinkedHashSet<Path>();var body=new LinkedHashSet<Path>();var deleted=new LinkedHashSet<Path>();
        var exports=new LinkedHashSet<String>();boolean namespace=false;
        for(var change:changes){
            var old=change.before();var now=change.after();Path file=change.file();
            boolean names=old==null||now==null||!old.exportedNames().equals(now.exportedNames());namespace|=names;
            if(now==null)deleted.add(file);
            if(names||!old.apiFingerprint().equals(now.apiFingerprint())){
                api.add(file);if(old!=null)exports.addAll(old.exportedNames());if(now!=null)exports.addAll(now.exportedNames());
            }else if(!old.equals(now))body.add(file);
        }
        var affected=new LinkedHashSet<Path>(body);affected.addAll(api);
        // A broad API/root event is discovery evidence. Reverse-file closure is authoritative only
        // until a dependant explicitly declares complete semantic-proof coverage. Precise consumers
        // are reconsidered from changed proof leaves after the new detached facts are admitted.
        if(environment==EnvironmentTransition.UNKNOWN)affected.addAll(postings.files());
        else if(!api.isEmpty()){
            var roots=new LinkedHashSet<>(api);roots.addAll(postings.unresolved(exports));
            affected.addAll(closure(roots,postings,proofCovered));
        }
        affected.removeAll(deleted);
        trace.count("affected_files",affected.size());trace.count("api_files",api.size());trace.count("body_files",body.size());
        return new Result(affected,api,body,deleted,environment==EnvironmentTransition.UNKNOWN,namespace);

        }
    }
    public static Set<Path> closure(Collection<Path> roots,Postings postings){
        return closure(roots,postings,ignored->false);
    }
    private static Set<Path> closure(Collection<Path> roots,Postings postings,
                                     java.util.function.Predicate<Path> proofCovered){
        var result=new LinkedHashSet<Path>();var queue=new ArrayDeque<>(roots);var rootsSet=new HashSet<Path>();
        for(Path root:roots)rootsSet.add(root.toAbsolutePath().normalize());
        while(!queue.isEmpty()){
            Path path=queue.removeFirst().toAbsolutePath().normalize();
            if(!rootsSet.contains(path)&&proofCovered.test(path))continue;
            if(result.add(path))queue.addAll(postings.dependants(path));
        }
        return Set.copyOf(result);
    }
    public static boolean matches(Set<String> unresolved,Set<String> exports){
        if(unresolved.contains("*")&&!exports.isEmpty())return true;
        for(String target:unresolved)for(String name:exports)
            if(target.equals(name)||target.startsWith(name+".")||name.startsWith(target+"."))return true;
        return false;
    }

    public record ProofConsumer(Path file,String id) implements Comparable<ProofConsumer> {
        public ProofConsumer {
            file=Objects.requireNonNull(file).toAbsolutePath().normalize();
            Objects.requireNonNull(id);
            if(id.isBlank())throw new IllegalArgumentException("Proof consumer id must not be blank");
        }
        @Override public int compareTo(ProofConsumer other){
            int path=file.toString().compareTo(other.file.toString());
            return path!=0?path:id.compareTo(other.id);
        }
    }
    /**
     * One registered reusable conclusion.
     *
     * {@code dependencies} is the proof certificate: <em>why</em> the result was valid. Its identity
     * is the certificate identity and is appropriate for "are my inputs unchanged?" checks.
     * {@code resultIdentity} is the canonical identity of the semantic <em>result</em> and is the only
     * identity propagated downstream. Two different certificates may justify the same result; when
     * a recomputation reproduces an equal result propagation stops (semantic fixed point).
     */
    public record ProofEvaluation(QueryProof dependencies,QueryProof.Key output,Hash256 resultIdentity) {
        public ProofEvaluation {
            Objects.requireNonNull(dependencies);Objects.requireNonNull(output);Objects.requireNonNull(resultIdentity);
            if(dependencies.dependencies().stream().anyMatch(value->value.key().equals(output)))
                throw new IllegalArgumentException("A proof consumer cannot depend directly on its own output");
        }
        /** Identity of the dependency certificate; never a substitute for {@link #resultIdentity()}. */
        public Hash256 certificateIdentity(){return dependencies.identity();}
    }

    /** Outcome of reconsidering one consumer whose inputs may have changed. */
    public sealed interface Recomputation {
        /** The result was recomputed now; its result identity decides whether propagation continues. */
        record Evaluated(ProofEvaluation evaluation) implements Recomputation {
            public Evaluated { Objects.requireNonNull(evaluation); }
        }
        /**
         * Inputs changed but the result can only be recomputed by the consumer's owner on its next
         * read (dependency validation on read). The registered certificate is retired; its output
         * becomes UNKNOWN until the owner registers a new evaluation.
         */
        record Deferred(String reason) implements Recomputation { }
        /** Recomputation is impossible; the caller must apply its conservative fallback. */
        record Unavailable(String reason) implements Recomputation { }

        static Recomputation evaluated(ProofEvaluation evaluation){return new Evaluated(evaluation);}
        static Recomputation deferred(String reason){return new Deferred(reason);}
        static Recomputation unavailable(String reason){return new Unavailable(reason);}
        static Recomputation of(Optional<ProofEvaluation> evaluation){
            return evaluation.<Recomputation>map(Evaluated::new).orElseGet(()->new Unavailable("recompute-unavailable"));
        }
    }
    /** Compatibility recomputer: empty means unavailable (conservative fallback). */
    @FunctionalInterface
    public interface ProofRecomputer {
        Optional<ProofEvaluation> recompute(ProofConsumer consumer)throws Exception;
    }
    @FunctionalInterface
    public interface ProofRecomputation {
        Recomputation recompute(ProofConsumer consumer)throws Exception;
    }
    /**
     * Result of one propagation wave.
     *
     * {@code recomputations} counts recompute calls including any re-execution forced by a
     * dependency discovered during the wave; {@code reordered} counts those re-executions.
     */
    public record ProofPropagation(Set<ProofConsumer> recomputed,Set<ProofConsumer> changed,
                                   Set<ProofConsumer> equal,Set<ProofConsumer> fallback,
                                   Set<ProofConsumer> deferred,int recomputations,int reordered) {
        public ProofPropagation {
            recomputed=ordered(recomputed);changed=ordered(changed);
            equal=ordered(equal);fallback=ordered(fallback);deferred=ordered(deferred);
            if(recomputations<0||reordered<0)throw new IllegalArgumentException("Negative propagation counters");
        }
        public ProofPropagation(Set<ProofConsumer> recomputed,Set<ProofConsumer> changed,
                                Set<ProofConsumer> equal,Set<ProofConsumer> fallback){
            this(recomputed,changed,equal,fallback,Set.of(),recomputed.size(),0);
        }
        private static Set<ProofConsumer> ordered(Collection<ProofConsumer> values){
            return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
        }
        private static Set<Path> orderedPaths(Collection<Path> values){
            return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
        }
        private static Set<Path> files(Collection<ProofConsumer> values){
            var result=new TreeSet<Path>();values.forEach(value->result.add(value.file()));return orderedPaths(result);
        }
        public Set<Path> changedFiles(){return files(changed);}
        public Set<Path> fallbackFiles(){return files(fallback);}
        public Set<Path> deferredFiles(){return files(deferred);}
        /** Consumers whose registered conclusion is no longer reusable as-is. */
        public Set<ProofConsumer> invalidated(){
            var result=new TreeSet<ProofConsumer>(changed);result.addAll(fallback);result.addAll(deferred);return ordered(result);
        }
    }
    public record ProofInvalidation(ProofPropagation propagation,Set<Path> coarseReanalyze) {
        public ProofInvalidation {
            Objects.requireNonNull(propagation);coarseReanalyze=Set.copyOf(coarseReanalyze);
        }
    }

    /**
     * Precise semantic dependency DAG.
     *
     * Leaf proof keys are maintained elsewhere (resident/indexed semantic state). This owner stores
     * only which reusable conclusions depended on which proof keys and the result identity each
     * conclusion published.
     *
     * Propagation is height ordered: every consumer has a height strictly greater than the height
     * of every registered producer of its dependencies, and a wave always recomputes the lowest
     * pending height first. A consumer is therefore never recomputed before every changed producer
     * it depends on has settled for the wave, so unequal path lengths cannot cause redundant
     * recomputation. Dependencies discovered during recomputation re-establish heights; if a newly
     * discovered producer is still pending, the consumer is re-queued above it rather than publishing
     * a premature result.
     *
     * An unavailable current leaf identity (UNKNOWN) never compares equal to anything; it retires the
     * affected consumers and, transitively, every consumer of their outputs.
     */
    public static final class ProofDag {
        private record Node(ProofEvaluation evaluation) { }
        private record Pending(int height,ProofConsumer consumer) implements Comparable<Pending> {
            @Override public int compareTo(Pending other){
                int compared=Integer.compare(height,other.height);
                return compared!=0?compared:consumer.compareTo(other.consumer);
            }
        }
        private final Map<ProofConsumer,Node> nodes=new HashMap<>();
        private final Map<QueryProof.Key,Set<ProofConsumer>> reverse=new HashMap<>();
        private final Map<QueryProof.Key,ProofConsumer> producers=new HashMap<>();
        private final Map<ProofConsumer,Integer> heights=new HashMap<>();

        public void register(ProofConsumer consumer,ProofEvaluation evaluation){
            Objects.requireNonNull(consumer);Objects.requireNonNull(evaluation);
            ProofConsumer producer=producers.get(evaluation.output());
            if(producer!=null&&!producer.equals(consumer))
                throw new IllegalArgumentException("Duplicate proof output producer: "+evaluation.output());
            var previous=nodes.get(consumer);
            if(previous!=null){
                unlink(consumer,previous.evaluation());
                producers.remove(previous.evaluation().output(),consumer);
            }
            nodes.put(consumer,new Node(evaluation));producers.put(evaluation.output(),consumer);link(consumer,evaluation);
            if(cycleFrom(consumer,new HashSet<>(),new HashSet<>())){
                unlink(consumer,evaluation);nodes.remove(consumer);producers.remove(evaluation.output(),consumer);
                if(previous!=null){nodes.put(consumer,previous);producers.put(previous.evaluation().output(),consumer);link(consumer,previous.evaluation());}
                else heights.remove(consumer);
                throw new IllegalArgumentException("Semantic proof dependencies must form a DAG");
            }
            updateHeight(consumer);
        }
        public void remove(ProofConsumer consumer){
            var previous=nodes.remove(Objects.requireNonNull(consumer));if(previous==null)return;
            unlink(consumer,previous.evaluation());producers.remove(previous.evaluation().output(),consumer);heights.remove(consumer);
        }
        public void removeFile(Path file){
            Path normalized=Objects.requireNonNull(file).toAbsolutePath().normalize();
            for(var consumer:new ArrayList<>(nodes.keySet()))if(consumer.file().equals(normalized))remove(consumer);
        }
        public Optional<ProofEvaluation> evaluation(ProofConsumer consumer){
            var node=nodes.get(consumer);return node==null?Optional.empty():Optional.of(node.evaluation());
        }
        public Set<ProofConsumer> consumers(QueryProof.Key key){
            return Collections.unmodifiableSet(new LinkedHashSet<>(
                    new TreeSet<>(reverse.getOrDefault(Objects.requireNonNull(key),Set.of()))));
        }
        /** Current semantic dependency leaves referenced by registered consumers. */
        public Set<QueryProof.Key> dependencyKeys(){
            return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(reverse.keySet())));
        }
        public boolean hasConsumers(Path file){
            Path normalized=Objects.requireNonNull(file).toAbsolutePath().normalize();return nodes.keySet().stream().anyMatch(value->value.file().equals(normalized));
        }
        /** Scheduling height; strictly above every registered producer of the consumer's dependencies. */
        public OptionalInt height(ProofConsumer consumer){
            Integer value=heights.get(consumer);return value==null?OptionalInt.empty():OptionalInt.of(value);
        }
        public int size(){return nodes.size();}
        public void clear(){nodes.clear();reverse.clear();producers.clear();heights.clear();}

        /** Propagate established leaf identities. */
        public ProofPropagation propagate(Map<QueryProof.Key,Hash256> currentLeaves,ProofRecomputer recomputer)throws Exception{
            Objects.requireNonNull(currentLeaves);Objects.requireNonNull(recomputer);
            var observed=new TreeMap<QueryProof.Key,Optional<Hash256>>();
            currentLeaves.forEach((key,value)->observed.put(key,Optional.of(Objects.requireNonNull(value))));
            return propagateObserved(observed,consumer->Recomputation.of(recomputer.recompute(consumer)));
        }

        /**
         * Propagate current leaf observations. An empty identity is UNKNOWN: affected consumers are
         * retired (deferred to their owner's next read) without consulting the recomputer, because
         * no equality can be established against unavailable evidence.
         */
        public ProofPropagation propagateObserved(Map<QueryProof.Key,Optional<Hash256>> currentLeaves,
                                                  ProofRecomputation recomputer)throws Exception{
            Objects.requireNonNull(currentLeaves);Objects.requireNonNull(recomputer);
            var wave=new Wave(recomputer);
            for(var entry:new TreeMap<>(currentLeaves).entrySet()){
                var current=Objects.requireNonNull(entry.getValue(),"leaf observation");
                for(var consumer:new ArrayList<>(reverse.getOrDefault(entry.getKey(),Set.of()))){
                    var node=nodes.get(consumer);if(node==null)continue;
                    if(current.isEmpty()){wave.retire(consumer,false);continue;}
                    var captured=node.evaluation().dependencies().identity(entry.getKey().domain(),entry.getKey().value());
                    if(captured.isEmpty()||!captured.get().equals(current.get()))wave.enqueue(consumer);
                }
            }
            return wave.run();
        }

        private final class Wave {
            final ProofRecomputation recomputer;
            final TreeSet<Pending> queue=new TreeSet<>();
            final Map<ProofConsumer,Integer> queued=new HashMap<>();
            final Set<ProofConsumer> recomputed=new TreeSet<>(),changed=new TreeSet<>(),equal=new TreeSet<>(),
                    fallback=new TreeSet<>(),deferred=new TreeSet<>();
            int recomputations,reordered;
            Wave(ProofRecomputation recomputer){this.recomputer=recomputer;}

            void enqueue(ProofConsumer consumer){
                if(!nodes.containsKey(consumer))return;
                int height=heights.getOrDefault(consumer,0);
                Integer previous=queued.put(consumer,height);
                if(previous!=null)queue.remove(new Pending(previous,consumer));
                queue.add(new Pending(height,consumer));
            }
            void dequeue(ProofConsumer consumer){
                Integer previous=queued.remove(consumer);
                if(previous!=null)queue.remove(new Pending(previous,consumer));
            }
            boolean pendingProducer(ProofConsumer consumer){
                var node=nodes.get(consumer);if(node==null)return false;
                for(var dependency:node.evaluation().dependencies().dependencies()){
                    var producer=producers.get(dependency.key());
                    if(producer!=null&&!producer.equals(consumer)&&queued.containsKey(producer))return true;
                }
                return false;
            }
            /**
             * Retire a consumer whose result can no longer be established now. Its output becomes
             * UNKNOWN, so every downstream consumer is retired with the same disposition.
             */
            void retire(ProofConsumer root,boolean unavailable){
                var work=new ArrayDeque<ProofConsumer>();work.add(root);
                while(!work.isEmpty()){
                    var consumer=work.removeFirst();var node=nodes.get(consumer);if(node==null)continue;
                    dequeue(consumer);
                    var output=node.evaluation().output();
                    remove(consumer);changed.remove(consumer);equal.remove(consumer);
                    if(unavailable){deferred.remove(consumer);fallback.add(consumer);}
                    else if(!fallback.contains(consumer))deferred.add(consumer);
                    work.addAll(reverse.getOrDefault(output,Set.of()));
                }
            }
            ProofPropagation run()throws Exception{
                int iterations=0,limit=16+4*(nodes.size()+1)*(nodes.size()+1);
                while(!queue.isEmpty()){
                    if(++iterations>limit)throw new IllegalStateException("Semantic proof propagation did not converge");
                    var pending=queue.pollFirst();queued.remove(pending.consumer());
                    var consumer=pending.consumer();var before=nodes.get(consumer);if(before==null)continue;
                    if(pendingProducer(consumer)){
                        // Defensive: heights guarantee producers are polled first; never recompute early.
                        updateHeight(consumer);enqueue(consumer);reordered++;continue;
                    }
                    recomputed.add(consumer);recomputations++;
                    var outcome=recomputer.recompute(consumer);
                    switch(outcome){
                        case Recomputation.Unavailable ignored -> retire(consumer,true);
                        case Recomputation.Deferred ignored -> retire(consumer,false);
                        case Recomputation.Evaluated evaluated -> settle(consumer,before.evaluation(),evaluated.evaluation());
                    }
                }
                return new ProofPropagation(recomputed,changed,equal,fallback,deferred,recomputations,reordered);
            }
            private void settle(ProofConsumer consumer,ProofEvaluation before,ProofEvaluation next){
                try{register(consumer,next);}
                catch(IllegalArgumentException invalid){retire(consumer,true);return;}
                // A dependency discovered by this recomputation may belong to a producer that has not
                // settled yet. Its height now exceeds that producer; publish only after it settles.
                if(pendingProducer(consumer)){enqueue(consumer);reordered++;return;}
                if(before.resultIdentity().equals(next.resultIdentity())&&before.output().equals(next.output())){
                    if(!changed.contains(consumer))equal.add(consumer);
                    return;
                }
                equal.remove(consumer);changed.add(consumer);
                var downstream=new TreeSet<ProofConsumer>();
                downstream.addAll(reverse.getOrDefault(before.output(),Set.of()));
                for(var dependent:reverse.getOrDefault(next.output(),Set.of())){
                    var node=nodes.get(dependent);if(node==null)continue;
                    var captured=node.evaluation().dependencies().identity(next.output().domain(),next.output().value());
                    if(captured.isEmpty()||!captured.get().equals(next.resultIdentity()))downstream.add(dependent);
                }
                downstream.remove(consumer);
                downstream.forEach(this::enqueue);
            }
        }

        /** Re-establish height(consumer) = 1 + max(height(producer)) and lift dependants above it. */
        private void updateHeight(ProofConsumer root){
            var work=new ArrayDeque<ProofConsumer>();work.add(root);
            while(!work.isEmpty()){
                var consumer=work.removeFirst();var node=nodes.get(consumer);if(node==null)continue;
                int height=0;
                for(var dependency:node.evaluation().dependencies().dependencies()){
                    var producer=producers.get(dependency.key());
                    if(producer!=null&&!producer.equals(consumer))height=Math.max(height,heights.getOrDefault(producer,0)+1);
                }
                Integer previous=heights.put(consumer,height);
                if(previous!=null&&previous==height&&consumer!=root)continue;
                for(var dependent:reverse.getOrDefault(node.evaluation().output(),Set.of()))
                    if(!dependent.equals(consumer)&&heights.getOrDefault(dependent,-1)<=height)work.addLast(dependent);
            }
        }

        private void link(ProofConsumer consumer,ProofEvaluation evaluation){
            for(var dependency:evaluation.dependencies().dependencies())
                reverse.computeIfAbsent(dependency.key(),ignored->new TreeSet<>()).add(consumer);
        }
        private void unlink(ProofConsumer consumer,ProofEvaluation evaluation){
            for(var dependency:evaluation.dependencies().dependencies()){
                var values=reverse.get(dependency.key());if(values==null)continue;
                values.remove(consumer);if(values.isEmpty())reverse.remove(dependency.key());
            }
        }
        private boolean cycleFrom(ProofConsumer current,Set<ProofConsumer> visiting,Set<ProofConsumer> done){
            if(done.contains(current))return false;if(!visiting.add(current))return true;
            var node=nodes.get(current);
            if(node!=null)for(var dependency:node.evaluation().dependencies().dependencies()){
                var producer=producers.get(dependency.key());
                if(producer!=null&&cycleFrom(producer,visiting,done))return true;
            }
            visiting.remove(current);done.add(current);return false;
        }
    }

    /** Actor-local canonical state. Focused observations supplement facts until a complete replacement arrives. */
    public static final class Live implements Postings {
        private final Map<Path,FileSemanticContribution> complete=new HashMap<>();
        private final Map<Path,Set<Path>> focused=new HashMap<>(),reverse=new HashMap<>();
        private final Map<Path,Set<Path>> pending=new HashMap<>();
        private final ProofDag proofs=new ProofDag();
        private final Set<Path> preciseProofCoverage=new HashSet<>();
        public FileSemanticContribution contribution(Path path){return complete.get(normalize(path));}
        public ProofDag proofs(){return proofs;}
        /**
         * Declare whether proof consumers fully cover this file's semantic dependency conclusions.
         * Until coverage is explicitly complete, the coarse reverse file graph remains authoritative.
         */
        public void proofCoverage(Path file,boolean completeCoverage){
            file=normalize(file);
            if(completeCoverage)preciseProofCoverage.add(file);else preciseProofCoverage.remove(file);
        }
        public boolean proofCovered(Path file){return preciseProofCoverage.contains(normalize(file));}
        public ProofInvalidation propagateProofChanges(Path changedFile,Map<QueryProof.Key,Hash256> leaves,
                                                       ProofRecomputer recomputer)throws Exception{
            changedFile=normalize(changedFile);
            var propagation=proofs.propagate(leaves,recomputer);
            var coarseRoots=new LinkedHashSet<Path>(propagation.fallbackFiles());
            coarseRoots.addAll(propagation.changedFiles());
            coarseRoots.addAll(propagation.deferredFiles());
            coarseRoots.add(changedFile);
            var reanalyze=new LinkedHashSet<Path>(coarseUnprovenClosure(coarseRoots));
            reanalyze.remove(changedFile);
            return new ProofInvalidation(propagation,reanalyze);
        }
        public Set<Path> coarseFallback(Collection<Path> roots){
            return coarseUnprovenClosure(roots);
        }
        private Set<Path> coarseUnprovenClosure(Collection<Path> roots){
            var result=new LinkedHashSet<Path>();var queue=new ArrayDeque<Path>();
            for(Path root:roots)queue.add(normalize(root));
            while(!queue.isEmpty()){
                Path current=queue.removeFirst();
                for(Path dependant:dependants(current)){
                    if(proofCovered(dependant))continue;
                    if(result.add(dependant))queue.addLast(dependant);
                }
            }
            return Set.copyOf(result);
        }
        public Set<Path> dependencies(Path path){
            path=normalize(path);var result=new HashSet<>(focused.getOrDefault(path,Set.of()));
            var value=complete.get(path);if(value!=null)result.addAll(value.dependencies());return Set.copyOf(result);
        }
        public Set<Path> dependants(Path path){return Set.copyOf(reverse.getOrDefault(normalize(path),Set.of()));}
        public Set<Path> files(){var result=new HashSet<>(complete.keySet());result.addAll(focused.keySet());return Set.copyOf(result);}
        public Set<Path> unresolved(Set<String> exports){
            var result=new LinkedHashSet<Path>();complete.forEach((file,value)->{if(matches(value.unresolvedTargets(),exports))result.add(file);});return Set.copyOf(result);
        }
        /** Files carrying negative/unresolved name evidence; used when source membership itself changes. */
        public Set<Path> unresolved(){
            var result=new LinkedHashSet<Path>();complete.forEach((file,value)->{if(!value.unresolvedTargets().isEmpty())result.add(file);});return Set.copyOf(result);
        }
        /** Initial attribution discovers existing files; only an observed edit is a change during bootstrap. */
        public Result resolve(FileSemanticContribution value){
            return resolve(value,false);
        }
        /**
         * Resolve a complete contribution when the caller has already admitted the corresponding
         * detached semantic facts and will propagate their changed proof leaves immediately.
         */
        public Result resolvePrecise(FileSemanticContribution value){
            return resolve(value,true);
        }
        private Result resolve(FileSemanticContribution value,boolean preciseLeavesAvailable){
            boolean initial=contribution(value.file())==null&&!pending(value.file());
            if(initial){
                replace(value);consumerResolved(value.file());
                return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);
            }
            return update(value,Completeness.COMPLETE,preciseLeavesAvailable);
        }
        public Result update(FileSemanticContribution value,Completeness completeness){
            return update(value,completeness,false);
        }
        private Result update(FileSemanticContribution value,Completeness completeness,boolean preciseLeavesAvailable){
            Objects.requireNonNull(value);Objects.requireNonNull(completeness);
            Path file=value.file();
            if(completeness==Completeness.FAILED)return new Result(Set.of(file),Set.of(),Set.of(),Set.of(),false,false);
            if(completeness==Completeness.FOCUSED){recordFocused(file,value.dependencies());return new Result(Set.of(),Set.of(),Set.of(),Set.of(),false,false);}
            var before=complete.get(file);
            // Decide with old edges still present, then replace exactly, including empty sets.
            // Proof-covered dependants are skipped only when the caller can immediately publish
            // precise semantic leaves from the corresponding detached semantic delta.
            var result=preciseLeavesAvailable
                    ?decide(List.of(new Change(before,value)),EnvironmentTransition.NONE,this,this::proofCovered)
                    :decide(List.of(new Change(before,value)),EnvironmentTransition.NONE,this);
            replace(value);pending.remove(file);consumerResolved(file);
            return result;
        }
        private void replace(FileSemanticContribution value){
            Path file=value.file();unlink(file);complete.put(file,value);focused.remove(file);link(file);
        }
        public void recordFocused(Path file,Set<Path> dependencies){
            file=normalize(file);unlink(file);var merged=new HashSet<>(focused.getOrDefault(file,Set.of()));
            for(Path dependency:dependencies)if(!normalize(dependency).equals(file))merged.add(normalize(dependency));
            focused.put(file,Set.copyOf(merged));link(file);
        }
        public Result remove(Path file){return remove(file,false);}
        /** Remove after the caller has captured/applied the resident semantic removal delta. */
        public Result removePrecise(Path file){return remove(file,true);}
        private Result remove(Path file,boolean preciseLeavesAvailable){
            file=normalize(file);var before=complete.get(file);
            var affected=new HashSet<>(preciseLeavesAvailable?coarseUnprovenClosure(Set.of(file)):closure(Set.of(file),this));affected.remove(file);
            var result=before==null?new Result(affected,Set.of(file),Set.of(),Set.of(file),false,true)
                    :preciseLeavesAvailable
                        ?decide(List.of(new Change(before,null)),EnvironmentTransition.NONE,this,this::proofCovered)
                        :decide(List.of(new Change(before,null)),EnvironmentTransition.NONE,this);
            unlink(file);complete.remove(file);focused.remove(file);pending.remove(file);proofs.removeFile(file);preciseProofCoverage.remove(file);return result;
        }
        public Set<Path> changed(Path file){
            file=normalize(file);
            // A source event is a mutation fence, not an invalidation decision. Retain the full
            // reverse closure only as pending scheduling evidence so the changed prerequisite is
            // attributed before its consumers. No dependant state is destroyed here: after the new
            // detached facts exist, resolve/update decides between precise proof propagation and
            // the conservative file closure.
            pending.put(file,closure(Set.of(file),this));
            return Set.of(file);
        }
        public Set<Path> prerequisites(Path file){
            file=normalize(file);var result=new HashSet<Path>();
            for(var entry:pending.entrySet())if(!file.equals(entry.getKey())&&entry.getValue().contains(file))result.add(entry.getKey());
            return Set.copyOf(result);
        }
        public boolean pending(Path file){return pending.containsKey(normalize(file));}
        /**
         * A complete consumer attribution observed all of its implicit source inputs. Remove that
         * consumer from outstanding prerequisite closures without claiming the changed root itself
         * has been semantically admitted.
         */
        public void consumerResolved(Path file){
            file=normalize(file);
            for(var entry:pending.entrySet())if(!entry.getKey().equals(file)){
                var remaining=new LinkedHashSet<>(entry.getValue());remaining.remove(file);
                entry.setValue(Set.copyOf(remaining));
            }
        }
        public int pendingCount(){return pending.size();}
        public int conditionalCount(){
            var files=new HashSet<Path>();pending.forEach((root,affected)->affected.stream().filter(p->!p.equals(root)).forEach(files::add));return files.size();
        }
        public void clear(){complete.clear();focused.clear();reverse.clear();pending.clear();proofs.clear();preciseProofCoverage.clear();}
        private void unlink(Path file){for(Path dependency:dependencies(file)){var values=reverse.get(dependency);if(values!=null){values.remove(file);if(values.isEmpty())reverse.remove(dependency);}}}
        private void link(Path file){for(Path dependency:dependencies(file))if(!dependency.equals(file))reverse.computeIfAbsent(dependency,k->new HashSet<>()).add(file);}
        public int edgeCount(){return reverse.values().stream().mapToInt(Set::size).sum();}
        private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    }
}
