package com.e9rental.service;

import java.io.StringReader;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Produces a short, inert preview for transient Gmail result rows. */
public final class GmailPreviewSanitizer {
    public static final int MAX_PREVIEW_CHARS=240;
    private static final int MAX_INPUT_CHARS=8192;
    private static final Pattern CONTROL=Pattern.compile("\\p{Cc}");
    private static final Pattern HTML_LIKE=Pattern.compile("(?is)<\\s*/?\\s*[a-z][a-z0-9]*\\b|<!--|<!doctype\\b");
    private static final Pattern URL=Pattern.compile("(?i)(?:https?://|www\\.)[^\\s<>\\\"']+|(?<![\\w@.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?::[0-9]{1,5})?[/?#][^\\s<>\\\"']*");
    private static final Pattern SPACE=Pattern.compile("\\s+");
    private static final Pattern NON_DISPLAY=Pattern.compile("(?is)<(script|style|head|noscript|title)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    private static final Set<String> SKIP=Set.of("script","style","head","noscript","title");

    private GmailPreviewSanitizer() {}

    public static String preview(String value) {
        if(value==null || value.isBlank())return "";
        String input=value.length()>MAX_INPUT_CHARS?value.substring(0,MAX_INPUT_CHARS):value;
        String readable=input;
        if(HTML_LIKE.matcher(input).find()) {
            try {readable=htmlToText(input);}
            catch(java.io.IOException malformed) {return "";}
        }
        readable=CONTROL.matcher(readable).replaceAll(" ");
        readable=URL.matcher(readable).replaceAll(" ");
        readable=SPACE.matcher(readable).replaceAll(" ").strip();
        if(readable.length()<=MAX_PREVIEW_CHARS)return readable;
        int end=MAX_PREVIEW_CHARS;
        if(end>0 && Character.isHighSurrogate(readable.charAt(end-1)))end--;
        int boundary=readable.lastIndexOf(' ',end);
        if(boundary>=MAX_PREVIEW_CHARS/2)end=boundary;
        return readable.substring(0,end).stripTrailing();
    }

    public static String label(String value,int maxLength) {
        if(value==null)return "";
        String clean=CONTROL.matcher(value).replaceAll("").strip();
        if(clean.length()<=maxLength)return clean;
        int end=maxLength;
        if(end>0 && Character.isHighSurrogate(clean.charAt(end-1)))end--;
        return clean.substring(0,end);
    }

    private static String htmlToText(String html) throws java.io.IOException {
        StringBuilder text=new StringBuilder();
        String safeHtml=NON_DISPLAY.matcher(html).replaceAll("");
        new ParserDelegator().parse(new StringReader(safeHtml),new HTMLEditorKit.ParserCallback() {
            private String skipped;
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                String name=tag.toString().toLowerCase(Locale.ROOT);
                if(SKIP.contains(name)){skipped=name;return;}
                if(skipped==null && text.length()>0 && Set.of("p","div","br","li","tr","h1","h2","h3","h4","h5","h6","blockquote").contains(name))text.append(' ');
            }
            @Override public void handleEndTag(HTML.Tag tag,int pos) {
                String name=tag.toString().toLowerCase(Locale.ROOT);
                if(Objects.equals(skipped,name)){skipped=null;return;}
                if(skipped==null && Set.of("p","div","br","li","tr","h1","h2","h3","h4","h5","h6","blockquote").contains(name))text.append(' ');
            }
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                if(skipped==null && "br".equalsIgnoreCase(tag.toString()))text.append(' ');
            }
            @Override public void handleText(char[] data,int pos) {if(skipped==null)text.append(data);}
        },true);
        return text.toString();
    }
}
