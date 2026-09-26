"""Disposable admission investigation. Run once on a clean throwaway checkout."""
from pathlib import Path
import re, shutil

ROOT = Path(__file__).resolve().parents[2]
FILES = {
    'jvmd-dist/src/main/java/dev/jvmd/dist/Application.java': ('application', 'completion analyzer maintainedResolution contextCacheIdentity createAnalyzerContext'),
    'jvmd-dist/src/main/java/dev/jvmd/dist/WorkspaceContextManager.java': ('context', 'context'),
    'jvmd-lsp/src/main/java/dev/jvmd/lsp/LspFacade.java': ('lsp', 'request'),
    'jvmd-analyzer/src/main/java/dev/jvmd/analyzer/Analyzer.java': ('analyzer', 'configure platformFingerprint semanticOwnerIdentity preciseClasspathSequence reconcileClasspath synchronizeKnownSources touch validatedInputs residentContextKey reusableDocumentSemantic documentProofCurrent maintainedHierarchyProofIdentity documentContextProof receiverProofIdentity resolutionPathIdentity hierarchyProofIdentity accessibilityProofIdentity namespaceDependencies namespaceTypeIdentity classpathProofIdentity maintainedQualifiedCompletion qualifiedDocumentSemantic residentQualifiedRows ensureSourceSemanticCurrent ensureHierarchySemanticCurrent ensureCompletionSemantics bindings semanticReadView queryHierarchyApi'),
    'jvmd-analyzer/src/main/java/dev/jvmd/analyzer/CompilerPool.java': ('compiler', 'configure observeSources inputSnapshot cacheValid execute'),
    'jvmd-core/src/main/java/dev/jvmd/core/LiveSourceState.java': ('source', 'settleWatchEvents reconcilePackages reconcilePackage reconcile observe refresh'),
    'jvmd-core/src/main/java/dev/jvmd/core/FileStateRegistry.java': ('files', 'hash stamp inventory'),
    'jvmd-index-rocks/src/main/java/dev/jvmd/index/rocks/RocksIndexStore.java': ('rocks', 'semanticClasspathSequence semanticClasspathSearch semanticClasspathIdentity semanticByScip semanticMembersByOwner'),
}

def mask_java(s):
    # Preserve offsets while excluding delimiters inside comments and Java literals.
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), s)

for filename, (prefix, names) in FILES.items():
    p = ROOT / filename
    if not p.exists():
        print('missing optional', filename); continue
    s = p.read_text(); masked = mask_java(s); edits = []
    for name in names.split():
        pattern = r'^    (?:@Override )?(?:public|private|protected) [^;{}=\n]*?\b' + name + r'\([^;{}]*?\)[^;{}]*?\{'
        if prefix == 'context': pattern = r'^    Analyzer.Context context\([^;{}]*?\)[^;{}]*?\{'
        found = list(re.finditer(pattern, masked, re.M))
        if not found: print('missing method', filename, name)
        for m in found:
            start = m.end(); depth = 1; end = start
            while depth:
                if masked[end] == '{': depth += 1
                elif masked[end] == '}': depth -= 1
                end += 1
            edits += [(start, '\n        try(var admissionSpan=dev.jvmd.core.RequestScope.stage("admission.'+prefix+'.'+name+'")){\n'), (end-1, '\n        }\n    ')]
    for pos, value in sorted(edits, reverse=True): s = s[:pos]+value+s[pos:]
    p.write_text(s)

# Individual proof domains are timed as well as the encompassing method.
p=ROOT/'jvmd-analyzer/src/main/java/dev/jvmd/analyzer/Analyzer.java'; s=p.read_text()
s=s.replace('Hash256 identity=switch(domain){', 'Hash256 identity;\n            try(var domainSpan=dev.jvmd.core.RequestScope.stage("admission.proof."+domain.name())){\n            identity=switch(domain){')
s=s.replace('if(!dependency.identity().equals(identity))return false;', '}\n            if(!dependency.identity().equals(identity))return false;')
s=s.replace('return new QueryProof(dependencies);', 'try(var digestSpan=dev.jvmd.core.RequestScope.stage("admission.proof.digest")){return new QueryProof(dependencies);}')
s=s.replace('var query=cached.snapshot().query(start);\n        if(!documentProofCurrent', 'var query=cached.snapshot().query(start);\n        dev.jvmd.core.RequestScope.count("proof_dependencies",query.proof().dependencies().size());\n        if(!documentProofCurrent')
s=s.replace('var domain=dependency.key().domain();', 'var domain=dependency.key().domain();\n            dev.jvmd.core.RequestScope.count("domain_"+domain.name(),1);')
s=s.replace('var observed=validatedInputs();String residentKey=', '''var observed=validatedInputs();
            try(var stateSpan=dev.jvmd.core.RequestScope.stage("admission.state")){
                var active=modules.get(context.generation());
                for(var entry:active.semantic.status().entrySet())
                    if(entry.getValue() instanceof Number number)stateSpan.count(entry.getKey(),number.longValue());
                stateSpan.count("document_contexts",active.documentSemantics.size());
                stateSpan.count("accessibility_entries",active.accessibility.entries.size());
                stateSpan.count("classpath_search_proofs",active.classpathSearchProofs.size());
                stateSpan.count("binding_computations",bindingComputations);
                stateSpan.count("input_epoch",observed.observation());
                stateSpan.count("environment_epoch",observed.environmentEpoch());
                stateSpan.revision(context.generation());
            }
            String residentKey=''')
p.write_text(s)

# The normal/frozen harness and oracle remain untouched. Only this disposable
# copy switches the admission wait. Both runs use the very same JVM/jars.
src=ROOT/'benchmarks/lsp-scenarios'; dst=ROOT/'benchmarks/semantic-admission-lsp'
shutil.copytree(src,dst,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.state','node_modules'))
if not (dst/'node_modules').exists(): (dst/'node_modules').symlink_to(src/'node_modules',target_is_directory=True)
p=dst/'harness/LspScenarioHarness.ts'; s=p.read_text()
s=s.replace('await this.waitForDiagnostics(uri,1,sequence);', 'if(process.env.ADMISSION_MODE!=="MUTATION_VISIBLE")await this.waitForDiagnostics(uri,1,sequence);')
s=s.replace('await this.waitForDiagnostics(uri,version,sequence);', 'if(process.env.ADMISSION_MODE!=="MUTATION_VISIBLE")await this.waitForDiagnostics(uri,version,sequence);')
s=s.replace('protected documentAdmissionBoundary(){', 'protected documentAdmissionBoundary(){\n    if(process.env.ADMISSION_MODE==="MUTATION_VISIBLE")return "diagnostic experiment: mutation causality only; exact-version diagnostic admission not awaited";')
s=s.replace('diagnosticsWaitedBeforeFirstUse:true', 'diagnosticsWaitedBeforeFirstUse:process.env.ADMISSION_MODE!=="MUTATION_VISIBLE"')
s=s.replace('diagnosticsWaited:true', 'diagnosticsWaited:process.env.ADMISSION_MODE!=="MUTATION_VISIBLE"')
s=s.replace('unavailable: versionless publishDiagnostics cannot prove the requested document version', 'unavailable: no exact-version diagnostic admission boundary established')
s=s.replace('const server=spawn(path.join(image,"bin/java"),[', 'const server=spawn(path.join(process.env.JAVA_HOME!,"bin/java"),[\n    "-Djvmd.trace=true",\n    "--enable-native-access=ALL-UNNAMED",\n    ...["api","util","code","main","platform"].map(p=>"--add-exports=jdk.compiler/com.sun.tools.javac."+p+"=ALL-UNNAMED"),\n    "-XX:StartFlightRecording=settings=profile,dumponexit=true,filename="+process.env.ADMISSION_JFR,')
s=s.replace('serverRevision:LspScenarioHarness.serverId', 'admissionMode:process.env.ADMISSION_MODE,\n      serverRevision:LspScenarioHarness.serverId')
p.write_text(s)
print('Instrumentation applied; production semantics and normal harness unchanged.')
