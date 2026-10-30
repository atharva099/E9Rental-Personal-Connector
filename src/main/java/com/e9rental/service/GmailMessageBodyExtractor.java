package com.e9rental.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.e9rental.connector.GmailRequestBudget;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

/** Converts bounded Gmail MIME text parts into inert readable text; never returns source HTML. */
@Component
public final class GmailMessageBodyExtractor {
    public static final int MAX_MIME_PARTS=500;
    public static final int MAX_BODY_CHARS=262_144;
    public static final int MAX_ATTACHMENT_NAMES=50;
    private static final int MAX_NAME_CHARS=200;
    private static final Set<String> BLOCKS=Set.of("p","div","br","li","ul","ol","tr","table",
        "h1","h2","h3","h4","h5","h6","blockquote","pre","hr");
    private static final Set<String> SKIP=Set.of("script","style","head","noscript","title");
    private static final Pattern CONTROL=Pattern.compile("\\p{Cc}");
    private static final Pattern NON_DISPLAY_CONTENT=Pattern.compile("(?is)<(script|style|head|noscript|title)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    // A text/plain MIME part can still contain a publisher's HTML template. Keep
    // this deliberately limited to recognizable HTML elements so comparisons
    // and other ordinary angle-bracket text are not reinterpreted as markup.
    private static final Pattern HTML_MARKUP=Pattern.compile("(?is)<!doctype\\s+html\\b|<\\s*/?\\s*(?:html|head|body|title|meta|link|style|script|noscript|div|span|p|br|hr|a|img|picture|source|h[1-6]|ul|ol|li|dl|dt|dd|table|thead|tbody|tfoot|tr|td|th|caption|blockquote|pre|code|strong|em|b|i|u|s|small|big|sup|sub|font|center|section|article|header|footer|nav|main|aside|form|input|button|label|select|option|textarea|iframe|object|embed|video|audio|canvas|svg|figure|figcaption|details|summary|address|wbr|time|mark|del|ins|abbr|acronym|cite|q|definition|fieldset|legend|progress|meter|template|data|output|ruby|rt|rp|bdi|bdo|map|area|colgroup|col)\\b(?:\\s|/?>)");

    public record Result(String text,boolean available,boolean incomplete,boolean truncated,
            List<String> attachmentNames,boolean attachmentNamesTruncated) {
        public Result { attachmentNames=List.copyOf(attachmentNames); }
    }
    private record TextPart(String mime,String data,String charset,boolean externalized) {}
    private static final class State {
        int parts;boolean incomplete,tooManyParts,attachmentNamesTruncated;
        final List<String> names=new ArrayList<>();final List<TextPart> text=new ArrayList<>();
    }

    public Result extract(JsonNode payload) {
        return extract(payload,null);
    }

    public Result extract(JsonNode payload,GmailRequestBudget budget) {
        ensure(budget);State state=new State();walk(payload,state,budget);
        StringBuilder output=new StringBuilder();boolean malformed=false;
        List<TextPart> plain=state.text.stream().filter(p->"text/plain".equals(p.mime())).toList();
        List<TextPart> selected=plain.isEmpty()?state.text.stream().filter(p->"text/html".equals(p.mime())).toList():plain;
        for(TextPart part:selected) {
            ensure(budget);
            if(part.externalized() || part.data().isBlank()){state.incomplete=true;continue;}
            try {
                byte[] bytes=Base64.getUrlDecoder().decode(part.data());
                Charset charset=part.charset().isBlank()?StandardCharsets.UTF_8:Charset.forName(part.charset());
                String text=new String(bytes,charset);
                if("text/html".equals(part.mime()) || ("text/plain".equals(part.mime()) && looksLikeHtml(text)))
                    text=htmlToText(text,budget);
                appendBounded(output,text);
            } catch(RuntimeException | java.io.IOException badMime) { malformed=true;state.incomplete=true; }
            ensure(budget);if(output.length()>=MAX_BODY_CHARS)break;
        }
        boolean truncated=state.tooManyParts || output.length()>=MAX_BODY_CHARS;
        if(state.tooManyParts)state.incomplete=true;
        String body=normalizeNewlines(output.toString());
        if(body.length()>MAX_BODY_CHARS){body=body.substring(0,MAX_BODY_CHARS);truncated=true;}
        boolean available=!body.isBlank() && !malformed;
        if(malformed)body="";
        if(state.text.isEmpty())state.incomplete=true;
        ensure(budget);return new Result(body,available,state.incomplete || truncated,truncated,state.names,state.attachmentNamesTruncated);
    }

    private void walk(JsonNode part,State state,GmailRequestBudget budget) {
        ensure(budget);
        if(part==null || !part.isObject()) { state.incomplete=true;return; }
        if(++state.parts>MAX_MIME_PARTS) { state.tooManyParts=true;return; }
        String filename=part.path("filename").asText("");
        if(!filename.isBlank()) {
            if(state.names.size()<MAX_ATTACHMENT_NAMES) {
                String name=safeName(filename);if(!name.isBlank() && !state.names.contains(name))state.names.add(name);
            } else state.attachmentNamesTruncated=true;
            return; // Metadata only; never read attachment bytes.
        }
        String mime=part.path("mimeType").asText("").toLowerCase(Locale.ROOT);
        if(mime.equals("text/plain") || mime.equals("text/html")) {
            String data=part.path("body").path("data").asText("");
            String attachment=part.path("body").path("attachmentId").asText("");
            boolean externalized=data.isBlank() && !attachment.isBlank();
            state.text.add(new TextPart(mime,data,charset(part),externalized));
            if(externalized)state.incomplete=true;
        } else if(!mime.startsWith("multipart/") && !mime.isBlank()) state.incomplete=true;
        JsonNode children=part.path("parts");
        if(children.isArray())for(JsonNode child:children) {
            if(state.parts>=MAX_MIME_PARTS){state.tooManyParts=true;break;}
            walk(child,state,budget);
        }
    }

    private static void ensure(GmailRequestBudget budget) {if(budget!=null)budget.ensureWithinDeadline();}

    private static String charset(JsonNode part) {
        for(JsonNode header:part.path("headers"))if("Content-Type".equalsIgnoreCase(header.path("name").asText())) {
            String value=header.path("value").asText("");
            var matcher=Pattern.compile("(?i)charset=[\"']?([A-Za-z0-9._-]+)").matcher(value);
            if(matcher.find())return matcher.group(1);
        }
        return "";
    }
    private static String safeName(String input) {
        String value=CONTROL.matcher(input.replace('\\','/')).replaceAll("");
        int slash=value.lastIndexOf('/');if(slash>=0)value=value.substring(slash+1);
        value=value.strip();if(value.length()>MAX_NAME_CHARS)value=value.substring(0,MAX_NAME_CHARS);
        return value;
    }
    private static void appendBounded(StringBuilder out,String value) {
        int remaining=MAX_BODY_CHARS-out.length();if(remaining<=0)return;
        int end=Math.min(value.length(),remaining);
        if(end>0 && end<value.length() && Character.isHighSurrogate(value.charAt(end-1)))end--;
        out.append(value,0,end);
    }
    private static String normalizeNewlines(String text) {
        return text.replace("\r\n","\n").replace('\r','\n').replaceAll("[ \\t]*\\n[ \\t]*","\n")
            .replaceAll("\\n{3,}","\n\n").strip();
    }
    private static boolean looksLikeHtml(String text) { return HTML_MARKUP.matcher(text).find(); }
    private static String htmlToText(String html,GmailRequestBudget budget) throws java.io.IOException {
        ensure(budget);
        StringBuilder result=new StringBuilder();
        // ParserDelegator can surface legacy style/script contents as text when it
        // repairs malformed HTML. Remove non-display blocks before parsing as well.
        String safeHtml=NON_DISPLAY_CONTENT.matcher(html).replaceAll("");
        new ParserDelegator().parse(new StringReader(safeHtml),new HTMLEditorKit.ParserCallback() {
            private String skipped;
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                ensure(budget);
                String name=tag.toString().toLowerCase(Locale.ROOT);
                if(SKIP.contains(name)){skipped=name;return;}
                if(skipped==null && BLOCKS.contains(name))newline();
            }
            @Override public void handleEndTag(HTML.Tag tag,int pos) {
                ensure(budget);
                String name=tag.toString().toLowerCase(Locale.ROOT);
                if(Objects.equals(skipped,name)){skipped=null;return;}
                if(skipped==null && BLOCKS.contains(name))newline();
            }
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                ensure(budget);
                if(skipped==null && "br".equalsIgnoreCase(tag.toString()))newline();
            }
            @Override public void handleText(char[] data,int pos) { ensure(budget);if(skipped==null)result.append(data); }
            private void newline(){if(result.length()>0 && result.charAt(result.length()-1)!='\n')result.append('\n');}
        },true);
        return result.toString();
    }
}
