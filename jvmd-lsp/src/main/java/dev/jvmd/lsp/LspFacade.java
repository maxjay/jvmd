package dev.jvmd.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.jvmd.core.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Implements 4.9: nine editor methods translated onto the resident session's core queries. */
public final class LspFacade {
    public static final List<String> TOKEN_TYPES=List.of("namespace","class","interface","enum","typeParameter","parameter","variable","property","enumMember","method","decorator");
    public static final List<String> TOKEN_MODIFIERS=List.of("declaration","static","readonly","abstract","deprecated","modification");
    private LspFacade() { }
    private static final class Query {
        final Dispatcher dispatcher;final Session session;int tier=2;final Set<String> warnings=new LinkedHashSet<>();
        Query(Dispatcher dispatcher,Session session){this.dispatcher=dispatcher;this.session=session;}
        Envelope call(String method,ObjectNode params)throws Exception{var answer=dispatcher.query(session,method,params);tier=Math.min(tier,answer.tier());warnings.addAll(answer.warnings());return answer;}
        JsonNode one(String method,ObjectNode params)throws Exception{return Json.MAPPER.valueToTree(call(method,params).result());}
        ObjectNode all(String method,ObjectNode params,String key)throws Exception{
            ObjectNode result=null;var values=Json.MAPPER.createArrayNode();long bytes=0;
            do{
                var answer=call(method,params);var row=(ObjectNode)Json.MAPPER.valueToTree(answer.result());if(result==null)result=row.deepCopy();
                for(var item:row.path(key)){bytes+=Json.MAPPER.writeValueAsBytes(item).length;if(values.size()>=100000||bytes>64L*1024*1024)throw new RpcException(-32005,"budget_exceeded",Map.of("reason","Editor query exceeds the bounded snapshot capacity","method",method));values.add(item);}
                if(!answer.truncated())break;if(answer.cursor()==null)throw new IllegalStateException("Truncated core query omitted cursor");params.put("cursor",answer.cursor());
            }while(true);
            result.set(key,values);return result;
        }
        Envelope finish(JsonNode value){var result=Json.MAPPER.createObjectNode();result.set("value",value);return new Envelope(tier,"live",false,null,List.copyOf(warnings),result);}
    }
    public static Map<String,Object> capabilities(){
        return Map.of("positionEncoding","utf-16","textDocumentSync",Map.of("openClose",true,"change",2,"save",Map.of("includeText",false)),
                "hoverProvider",true,"definitionProvider",true,"referencesProvider",true,"renameProvider",Map.of("prepareProvider",true),
                "documentSymbolProvider",true,"completionProvider",Map.of("triggerCharacters",List.of("."),"resolveProvider",true),
                "signatureHelpProvider",Map.of("triggerCharacters",List.of("(",",","<"),"retriggerCharacters",List.of(",")),
                "semanticTokensProvider",Map.of("legend",Map.of("tokenTypes",TOKEN_TYPES,"tokenModifiers",TOKEN_MODIFIERS),"full",true,"range",false));
    }
    private static ObjectNode params(Path path){return Json.MAPPER.createObjectNode().put("path",path.toString());}
    public static Path path(Session session,String uri){
        URI parsed;try{parsed=URI.create(uri);}catch(IllegalArgumentException error){throw RpcException.invalid("Invalid document URI");}
        if(!"file".equalsIgnoreCase(parsed.getScheme()))throw RpcException.invalid("Only workspace file URIs can be queried");
        Path file;try{file=Path.of(parsed).toAbsolutePath().normalize();}catch(Exception error){throw RpcException.invalid("Invalid file URI");}
        var manifest=(WorkspaceManifest)session.state("workspace_manifest");if(manifest==null)manifest=new WorkspaceManifest(List.of(session.root()),true);
        return manifest.resolve(session.root(),file.toString());
    }
    private static String uri(String path){return path.startsWith("jar:")||path.startsWith("file:")?path:Path.of(path).toUri().toString();}
    private static JsonNode range(JsonNode symbol){
        if(symbol.hasNonNull("name_range"))return symbol.path("name_range");int line=Math.max(0,symbol.path("line").asInt(1)-1),character=Math.max(0,symbol.path("character").asInt());
        return Json.MAPPER.valueToTree(Map.of("start",Map.of("line",line,"character",character),"end",Map.of("line",line,"character",character+symbol.path("name").asText().length())));
    }
    private static JsonNode location(JsonNode symbol){
        if(!symbol.hasNonNull("source_file"))return Json.MAPPER.nullNode();
        return Json.MAPPER.valueToTree(Map.of("uri",uri(symbol.path("source_file").asText()),"range",range(symbol)));
    }
    private static int symbolKind(String kind){return switch(kind){case "package"->4;case "class","annotation"->5;case "method"->6;case "field"->8;case "ctor"->9;case "enum"->10;case "interface"->11;case "enumconst"->22;case "record"->23;case "type_parameter"->26;default->13;};}
    private static int completionKind(String kind){return switch(kind){case "method"->2;case "ctor"->4;case "field"->5;case "class","record","annotation"->7;case "interface"->8;case "package"->9;case "enum"->13;case "enumconst"->20;case "type_parameter"->25;default->6;};}
    private static Map<String,Object> importEdit(String source,String fqn){
        int offset=0;String text="import "+fqn+";\n\n";
        var firstImport=java.util.regex.Pattern.compile("(?m)^\\s*import\\s+").matcher(source);
        if(firstImport.find()){offset=firstImport.start();text="import "+fqn+";\n";}
        else{
            var packageStatement=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*;").matcher(source);
            if(packageStatement.find()){offset=packageStatement.end();text="\n\nimport "+fqn+";";}
        }
        var position=Documents.position(source,offset);
        return Map.of("range",Map.of("start",position,"end",position),"newText",text);
    }
    public static Envelope request(Dispatcher dispatcher,Session session,Documents documents,JsonNode request)throws Exception{
        String method=Dispatcher.required(request,"method");JsonNode nativeParams=request.path("params");var query=new Query(dispatcher,session);
        if(method.equals("initialize"))return query.finish(Json.MAPPER.valueToTree(Map.of("capabilities",capabilities(),"serverInfo",Map.of("name","jvmd","version","0.1.0"))));
        if(!Set.of("textDocument/documentSymbol","textDocument/semanticTokens/full","textDocument/completion","completionItem/resolve","textDocument/signatureHelp","textDocument/hover","textDocument/definition","textDocument/references","textDocument/rename","textDocument/prepareRename").contains(method))throw new RpcException(-32601,"Method not found",Map.of("method",method));
        if(method.equals("completionItem/resolve")){
            if(!nativeParams.isObject())throw RpcException.invalid("Completion item must be an object");
            String ref=Dispatcher.required(nativeParams.path("data"),"scip");
            String expected=nativeParams.path("data").path("resolution_identity").asText("");
            var described=query.one("symbol.describe",Json.MAPPER.createObjectNode().put("ref",ref).put("detail","summary").put("doc_depth",0));
            String current=described.path("resolution_identity").asText("");
            if(!expected.isEmpty()&&!expected.equals(current))
                throw new RpcException(-32801,"Completion item is stale",Map.of("scip",ref,"expected",expected,"current",current));
            var item=(ObjectNode)nativeParams.deepCopy();
            if(!item.hasNonNull("detail")&&described.hasNonNull("signature"))item.put("detail",described.path("signature").asText());
            String doc=described.path("doc").asText("");
            if(!doc.isEmpty())item.set("documentation",Json.MAPPER.valueToTree(Map.of("kind","markdown","value",doc)));
            return query.finish(item);
        }
        Path file=path(session,Dispatcher.required(nativeParams.path("textDocument"),"uri"));var arguments=params(file);
        if(method.equals("textDocument/documentSymbol")){
            var outline=query.all("symbol.overview",arguments.put("depth",10).put("limit",1000),"symbols");var output=Json.MAPPER.createArrayNode();var parents=new ArrayDeque<JsonNode>();
            boolean hierarchy=request.path("client").path("textDocument").path("documentSymbol").path("hierarchicalDocumentSymbolSupport").asBoolean();
            for(var symbol:outline.path("symbols")){
                var row=Json.MAPPER.createObjectNode().put("name",symbol.path("name").asText()).put("kind",symbolKind(symbol.path("kind").asText()));
                if(hierarchy){
                    row.put("detail",symbol.path("signature").asText());row.set("range",symbol.path("range"));row.set("selectionRange",range(symbol));row.putArray("children");
                    while(!parents.isEmpty()&&parents.getLast().path("end").asInt()<symbol.path("end").asInt())parents.removeLast();
                    if(parents.isEmpty())output.add(row);else ((ArrayNode)parents.getLast().path("row").path("children")).add(row);
                    var parent=Json.MAPPER.createObjectNode().put("end",symbol.path("end").asInt());parent.set("row",row);parents.add(parent);
                }else{row.set("location",location(symbol));row.put("containerName",symbol.path("declaring").asText(""));output.add(row);}
            }return query.finish(output);
        }
        if(method.equals("textDocument/semanticTokens/full")){
            var tokens=query.all("symbol.semanticTokens",arguments.put("limit",2000),"data");return query.finish(tokens);
        }
        var position=nativeParams.path("position");int line=Dispatcher.bounded(position,"line",0,Integer.MAX_VALUE),character=Dispatcher.bounded(position,"character",0,Integer.MAX_VALUE);
        Documents.offset(documents.text(file),new Documents.Position(line,character));arguments.put("line",line).put("character",character);
        if(method.equals("textDocument/completion")){
            var answer=query.call("symbol.completion",arguments.put("limit",50));JsonNode completion=Json.MAPPER.valueToTree(answer.result());var items=Json.MAPPER.createArrayNode();
            for(var symbol:completion.path("items")){
                String name=symbol.path("name").asText(),semanticLabel=symbol.path("editor_label").asText(symbol.path("label").asText(name)),kind=symbol.path("kind").asText();
                boolean type=Set.of("class","interface","enum","record","annotation","type_parameter").contains(kind);
                var item=Json.MAPPER.createObjectNode()
                        .put("label",type?name:semanticLabel)
                        .put("kind",completionKind(kind));
                if(type&&!semanticLabel.equals(name))item.put("detail",semanticLabel);
                else if(!type)item.put("detail",semanticLabel);
                item.set("textEdit",Json.MAPPER.valueToTree(Map.of("range",completion.path("range"),"newText",name)));
                var data=Json.MAPPER.createObjectNode().put("scip",symbol.path("scip").asText());
                if(symbol.hasNonNull("resolution_identity"))data.put("resolution_identity",symbol.path("resolution_identity").asText());
                item.set("data",data);
                if(symbol.hasNonNull("import"))item.set("additionalTextEdits",Json.MAPPER.valueToTree(List.of(importEdit(documents.text(file),symbol.path("import").asText()))));
                items.add(item);
            }return query.finish(Json.MAPPER.valueToTree(Map.of("isIncomplete",answer.truncated(),"items",items)));
        }
        if(method.equals("textDocument/signatureHelp")){
            var signatures=query.one("symbol.signatureHelp",arguments);var items=Json.MAPPER.createArrayNode();
            for(var signature:signatures.path("signatures")){var row=Json.MAPPER.createObjectNode().put("label",signature.path("label").asText()).put("activeParameter",signature.path("activeParameter").asInt());row.set("parameters",signature.path("parameters"));if(signature.hasNonNull("doc"))row.set("documentation",Json.MAPPER.valueToTree(Map.of("kind","markdown","value",signature.path("doc").asText())));items.add(row);}
            return query.finish(items.isEmpty()?Json.MAPPER.nullNode():Json.MAPPER.valueToTree(Map.of("signatures",items,"activeSignature",signatures.path("activeSignature").asInt(),"activeParameter",signatures.path("activeParameter").asInt())));
        }
        if(!Set.of("textDocument/hover","textDocument/definition","textDocument/references","textDocument/rename","textDocument/prepareRename").contains(method))throw new RpcException(-32601,"Method not found",Map.of("method",method));
        var symbol=query.one("symbol.atPosition",arguments);
        if(symbol.path("ambiguous").asBoolean()&&!symbol.hasNonNull("scip")){
            if(method.equals("textDocument/hover")){
                var signatures=new ArrayList<String>();for(var candidate:symbol.path("candidates"))signatures.add(candidate.path("signature").asText());
                return query.finish(Json.MAPPER.valueToTree(Map.of("contents",Map.of("kind","markdown","value","~~~java\n"+String.join("\n",signatures)+"\n~~~"),"range",symbol.path("occurrence").path("range"))));
            }
            if(method.equals("textDocument/definition")||method.equals("textDocument/references")){
                var locations=new LinkedHashMap<String,JsonNode>();
                for(var candidate:symbol.path("candidates")){
                    if(method.equals("textDocument/definition")){
                        if(!candidate.hasNonNull("source_file"))candidate=query.one("symbol.describe",Json.MAPPER.createObjectNode().put("ref",candidate.path("scip").asText()).put("detail","summary").put("doc_depth",1));
                        var found=location(candidate);if(!found.isNull())locations.putIfAbsent(found.toString(),found);
                    }else{
                        var found=query.all("symbol.occurrences",Json.MAPPER.createObjectNode().put("ref",candidate.path("scip").asText()).put("include_declaration",nativeParams.path("context").path("includeDeclaration").asBoolean()).put("limit",1000),"occurrences");
                        for(var occurrence:found.path("occurrences")){JsonNode value=Json.MAPPER.valueToTree(Map.of("uri",uri(occurrence.path("file").asText()),"range",occurrence.path("range")));locations.putIfAbsent(value.toString(),value);}
                    }
                }
                return query.finish(Json.MAPPER.valueToTree(locations.values()));
            }
            if(method.equals("textDocument/rename"))throw RpcException.invalid("This import names multiple overloads; select a declaration or call to rename one overload");
            return query.finish(Json.MAPPER.nullNode());
        }
        if(!symbol.hasNonNull("scip"))return query.finish(Json.MAPPER.nullNode());String ref=symbol.path("scip").asText();
        if(method.equals("textDocument/hover")){
            var described=query.one("symbol.describe",Json.MAPPER.createObjectNode().put("ref",ref).put("detail","full").put("doc_depth",0));String signature=described.path("signature").asText(symbol.path("signature").asText());String doc=described.path("doc").asText("");
            String markdown="~~~java\n"+signature+"\n~~~"+(doc.isEmpty()?"":"\n\n"+doc)+(query.warnings.isEmpty()?"":"\n\n"+String.join("\n\n",query.warnings));
            var result=Json.MAPPER.createObjectNode();result.set("contents",Json.MAPPER.valueToTree(Map.of("kind","markdown","value",markdown)));result.set("range",symbol.path("occurrence").path("range"));return query.finish(result);
        }
        if(method.equals("textDocument/definition")){
            if(!symbol.hasNonNull("source_file"))symbol=query.one("symbol.describe",Json.MAPPER.createObjectNode().put("ref",ref).put("detail","summary").put("doc_depth",1));return query.finish(location(symbol));
        }
        if(method.equals("textDocument/references")){
            var occurrences=query.all("symbol.occurrences",Json.MAPPER.createObjectNode().put("ref",ref).put("include_declaration",nativeParams.path("context").path("includeDeclaration").asBoolean()).put("limit",1000),"occurrences");var locations=Json.MAPPER.createArrayNode();
            for(var occurrence:occurrences.path("occurrences"))locations.add(Json.MAPPER.valueToTree(Map.of("uri",uri(occurrence.path("file").asText()),"range",occurrence.path("range"))));return query.finish(locations);
        }
        var occurrence=symbol.path("occurrence");if(symbol.path("kind").asText().equals("ctor")){symbol=query.one("symbol.describe",Json.MAPPER.createObjectNode().put("ref",symbol.path("fqn").asText()).put("doc_depth",0));ref=symbol.path("scip").asText();}
        if(!symbol.hasNonNull("source_file")||symbol.path("source_file").asText().startsWith("jar:"))return query.finish(Json.MAPPER.nullNode());
        try{path(session,uri(symbol.path("source_file").asText()));}catch(RpcException outside){return query.finish(Json.MAPPER.nullNode());}
        if(method.equals("textDocument/prepareRename"))return query.finish(Json.MAPPER.valueToTree(Map.of("range",occurrence.path("range"),"placeholder",occurrence.path("token").asText())));
        var plan=query.one("edit.rename",Json.MAPPER.createObjectNode().put("ref",ref).put("new_name",Dispatcher.required(nativeParams,"newName")).put("dry_run",true));
        boolean versioned=request.path("client").path("workspace").path("workspaceEdit").path("documentChanges").asBoolean(),renameFiles=false;for(var operation:request.path("client").path("workspace").path("workspaceEdit").path("resourceOperations"))if(operation.asText().equals("rename"))renameFiles=true;
        var changes=Json.MAPPER.createObjectNode();var documentChanges=Json.MAPPER.createArrayNode();
        for(var change:plan.path("changes")){
            Path changed=Path.of(change.path("path").asText());String fileUri=changed.toUri().toString();var edits=Json.MAPPER.createArrayNode();for(var edit:change.path("text_edits"))edits.add(Json.MAPPER.valueToTree(Map.of("range",edit.path("range"),"newText",edit.path("new_text").asText())));
            if(versioned){var identifier=Json.MAPPER.createObjectNode().put("uri",fileUri);identifier.set("version",Json.MAPPER.valueToTree(documents.version(changed)));documentChanges.add(Json.MAPPER.valueToTree(Map.of("textDocument",identifier,"edits",edits)));}
            else changes.set(fileUri,edits);
            if(change.hasNonNull("new_path")){
                if(!versioned||!renameFiles)throw new RpcException(-32003,"unsupported_capability",Map.of("capability","rename","reason","This client does not support file rename edits"));
                documentChanges.add(Json.MAPPER.valueToTree(Map.of("kind","rename","oldUri",fileUri,"newUri",Path.of(change.path("new_path").asText()).toUri().toString())));
            }
        }
        return query.finish(Json.MAPPER.valueToTree(versioned?Map.of("documentChanges",documentChanges):Map.of("changes",changes)));
    }
    public static Envelope diagnostics(Dispatcher dispatcher,Session session,Documents documents,JsonNode request)throws Exception{
        Path file=path(session,Dispatcher.required(request,"uri"));var query=new Query(dispatcher,session);
        var params=Json.MAPPER.createObjectNode().put("limit",1000);params.putArray("paths").add(file.toString());var answer=query.all("diag.get",params,"diagnostics");var result=Json.MAPPER.createObjectNode().put("uri",request.path("uri").asText());var diagnostics=result.putArray("diagnostics");
        for(var problem:answer.path("diagnostics")){
            String source=problem.path("file").asText();if(!source.isEmpty()&&!uri(source).equals(file.toUri().toString()))continue;
            long start=Math.max(0,problem.path("start").asLong()),end=Math.max(start,problem.path("end").asLong());
            var range=Json.MAPPER.valueToTree(Map.of("start",Documents.position(documents.text(file),start),"end",Documents.position(documents.text(file),end)));
            int severity=switch(problem.path("kind").asText()){case "ERROR"->1;case "WARNING","MANDATORY_WARNING"->2;default->3;};
            diagnostics.add(Json.MAPPER.valueToTree(Map.of("range",range,"severity",severity,"code",problem.path("code").asText(),"source","jvmd live","message",problem.path("message").asText(),"data",Map.of("tier",problem.path("tier").asInt(),"source",problem.path("source").asText("live")))));
        }
        for(String warning:query.warnings)diagnostics.add(Json.MAPPER.valueToTree(Map.of("range",Map.of("start",Map.of("line",0,"character",0),"end",Map.of("line",0,"character",0)),"severity",2,"source","jvmd","code","jvmd.fidelity","message",warning,"data",Map.of("tier",query.tier,"source","live"))));
        if(documents.version(file)!=null)result.put("version",documents.version(file));return query.finish(result);
    }
}
