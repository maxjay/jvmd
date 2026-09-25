package dev.jvmd.analyzer;

import java.util.Objects;

/**
 * Normalizes an editor completion cursor into the smallest stable source probe needed by semantic
 * context attribution. It owns source repair only; it performs no name/type/candidate resolution.
 */
public final class CompletionProbe {
    public static final String MARKER="__jvmd_completion__";

    public record Shape(String source,int selectorStart,int selectorEnd,int focusCursor,String prefix,boolean qualified) {
        public Shape {
            Objects.requireNonNull(source);Objects.requireNonNull(prefix);
            if(selectorStart<0||selectorEnd<selectorStart||focusCursor<0)throw new IllegalArgumentException("Invalid completion probe");
        }
    }

    private CompletionProbe(){}

    public static Shape create(String text,int cursor){
        Objects.requireNonNull(text);
        if(cursor<0||cursor>text.length())throw new IndexOutOfBoundsException(cursor);
        int start=cursor,end=cursor;
        while(start>0&&Character.isJavaIdentifierPart(text.codePointBefore(start)))
            start-=Character.charCount(text.codePointBefore(start));
        while(end<text.length()&&Character.isJavaIdentifierPart(text.codePointAt(end)))
            end+=Character.charCount(text.codePointAt(end));
        String prefix=text.substring(start,cursor);

        int selector=start-1;
        while(selector>=0&&Character.isWhitespace(text.charAt(selector)))selector--;
        boolean qualified=selector>=0&&text.charAt(selector)=='.';

        String replacement=qualified?MARKER+"()":MARKER;
        if(qualified&&needsTerminator(text,end))replacement+=";";
        String source=text.substring(0,start)+replacement+text.substring(end);
        return new Shape(source,start,end,start,prefix,qualified);
    }

    /**
     * A bare/incomplete member access at statement/return end needs a terminator after becoming an
     * unresolved invocation. Inside argument lists, conditionals, existing statements, etc. the
     * surrounding delimiter remains authoritative.
     */
    private static boolean needsTerminator(String text,int offset){
        int next=nextSignificant(text,offset);
        return next>=text.length()||text.charAt(next)=='}';
    }

    private static int nextSignificant(String text,int offset){
        int i=offset;
        while(i<text.length()){
            char c=text.charAt(i);
            if(Character.isWhitespace(c)){i++;continue;}
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='/'){
                int newline=text.indexOf('\n',i+2);return newline<0?text.length():nextSignificant(text,newline+1);
            }
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='*'){
                int end=text.indexOf("*/",i+2);return end<0?text.length():nextSignificant(text,end+2);
            }
            return i;
        }
        return i;
    }
}
