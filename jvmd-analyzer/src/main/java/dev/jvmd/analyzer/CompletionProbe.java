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
        if(qualified&&needsTerminator(text,start,end))replacement+=";";
        String source=text.substring(0,start)+replacement+text.substring(end);
        return new Shape(source,start,end,start,prefix,qualified);
    }

    /**
     * A bare/incomplete member access at statement/return end needs a terminator after becoming an
     * unresolved invocation. Inside argument lists, conditionals, existing statements, etc. the
     * surrounding delimiter remains authoritative.
     */
    private static boolean needsTerminator(String text,int selectorStart,int offset){
        int next=nextSignificant(text,offset);
        if(next>=text.length()||text.charAt(next)=='}')return true;
        char token=text.charAt(next);
        if(token==';'||token==')'||token==']'||token==','||token==':'||token=='?'||token=='.')return false;
        if(sameLine(text,offset,next))return false;
        // A more-indented following token is plausibly a line continuation. Same/dedented code is
        // a confidently separate statement boundary, so terminate the repaired invocation.
        return indentation(text,next)<=indentation(text,selectorStart);
    }

    private static boolean sameLine(String text,int first,int second){
        int end=text.indexOf('\n',Math.min(first,text.length()));
        return end<0||second<end;
    }
    private static int indentation(String text,int offset){
        int line=offset;
        while(line>0&&text.charAt(line-1)!='\n')line--;
        int width=0;
        for(int i=line;i<text.length()&&i<offset;i++){
            char c=text.charAt(i);
            if(c==' ')width++;
            else if(c=='\t')width+=4;
            else break;
        }
        return width;
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
