package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.mcp.McpTools;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: the fourteen names and schemas are a frozen, snapshot-tested contract. */
@Tag("phase-8")
class McpToolSchemasTest {
    @Test void schemasMatchTheCommittedSnapshotAndMapEveryTool()throws Exception{
        var tools=Json.MAPPER.createArrayNode();for(var entry:McpTools.catalog())tools.add(entry.path("tool"));
        try(var snapshot=getClass().getResourceAsStream("/mcp-tools.json")){assertThat(tools).isEqualTo(Json.MAPPER.readTree(snapshot));}
        assertThat(tools).hasSize(14);
        Map<String,Map<String,Object>> inputs=Map.ofEntries(
            Map.entry("overview",Map.of("path","Example.java")),Map.entry("find",Map.of("name_path","Example")),
            Map.entry("describe",Map.of("ref","Example")),Map.entry("references",Map.of("ref","Example")),
            Map.entry("hierarchy",Map.of("ref","Example")),Map.entry("deps",Map.of()),
            Map.entry("replace_body",Map.of("ref","Example/value()","body","{return 1;}")),
            Map.entry("insert",Map.of("ref","Example","position","into","code","int value;")),
            Map.entry("rename",Map.of("ref","Example","new_name","Renamed")),
            Map.entry("edit",Map.of("text_edits",List.of(Map.of("path","Example.java","start",0,"end",0,"new_text"," ")))),
            Map.entry("diagnostics",Map.of()),Map.entry("status",Map.of()),Map.entry("run",Map.of("target","Example")),
            Map.entry("debug",Map.of("session","s1r1","op","stop")));
        for(var entry:McpTools.catalog()){
            String name=entry.path("tool").path("name").asText();var request=McpTools.request("s1",name,Json.MAPPER.valueToTree(inputs.get(name)),Json.MAPPER.getNodeFactory().numberNode(9));
            assertThat(request.path("method").asText()).isEqualTo(entry.path("method").asText());assertThat(request.path("params").path("session").asText()).isEqualTo("s1");
            if(name.equals("debug"))assertThat(request.path("params").path("run_session").asText()).isEqualTo("s1r1");
        }
        assertThat(tools.toString()).contains("verified=true");
    }
    @Test void argumentValidationCatchesUnknownRenamedAndMalformedFields(){
        for(var input:List.of(Map.of("name","Example"),Map.of("name_path","Example","limit",0),Map.of("name_path","Example","limit","5"),Map.of("name_path","Example","scope","global")))
            assertThatThrownBy(()->McpTools.request("s1","find",Json.MAPPER.valueToTree(input),Json.MAPPER.nullNode())).isInstanceOf(RpcException.class);
        assertThatThrownBy(()->McpTools.request("s1","overview",Json.MAPPER.valueToTree(Map.of("path","Example.java","package","sample")),Json.MAPPER.nullNode())).isInstanceOf(RpcException.class);
        assertThatThrownBy(()->McpTools.request("s1","debug",Json.MAPPER.valueToTree(Map.of("session","s1r1","op","eval","extra",true)),Json.MAPPER.nullNode())).isInstanceOf(RpcException.class);
    }
}
