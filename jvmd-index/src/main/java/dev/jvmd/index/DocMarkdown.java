package dev.jvmd.index;
/** Implements 4.5: render parsed Javadoc as Markdown while retaining inheritDoc for query time. */
public final class DocMarkdown {
    private DocMarkdown() { }
    public static String render(String doc) {
        if (doc == null) return null;
        return doc.replaceAll("(?s)\\{@code\\s+([^}]+)}", "`$1`")
                .replaceAll("(?s)\\{@literal\\s+([^}]+)}", "$1")
                .replaceAll("\\{@link(?:plain)?\\s+([^}]+)}", "`$1`")
                .replaceAll("(?i)<p(?:\\s[^>]*)?>", "\n\n").replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)<(?:pre|code)>", "`").replaceAll("(?i)</(?:pre|code)>", "`")
                .replaceAll("(?i)<li>", "\n- ").replaceAll("(?i)</?(?:ul|ol|li)>", "")
                .replaceAll("(?i)</?(?:b|strong)>", "**").replaceAll("(?i)</?(?:i|em)>", "*")
                .replaceAll("(?m)^@param\\s+(\\S+)\\s*", "\n- **$1:** ")
                .replaceAll("(?m)^@return\\s*", "\n**Returns:** ")
                .replaceAll("(?m)^@throws\\s+(\\S+)\\s*", "\n**Throws `$1`:** ")
                .replaceAll("(?m)^@(?:exception)\\s+(\\S+)\\s*", "\n**Throws `$1`:** ")
                .replaceAll("(?m)^@see\\s*", "\n**See:** ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }
    public static String summary(String doc) { if(doc==null)return null;int p=doc.indexOf("\n\n");return p<0?doc:doc.substring(0,p); }
}
