package dev.jvmd.index;

import java.util.*;

/** Shared keyed symbol operations. Cursors belong to the view that returned them. */
public interface SymbolReadView {
    record Page(List<Map<String,Object>> symbols,String cursor) {public Page{symbols=List.copyOf(symbols);}}
    Map<String,Object> byScip(String scip)throws Exception;
    /** Whether this view owns a declaration, rather than merely observing a reference. */
    boolean declares(String scip)throws Exception;
    Page find(String query,boolean substring,Set<String> kinds,int limit,String cursor)throws Exception;
    Page descendants(String path,int depth,Set<String> kinds,int limit,String cursor)throws Exception;
}
