package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;

/** In-memory text preview only. No fetch, persistence, rights approval, or READY transition. */
public final class ReviewOnlyHtmlBodyExtractor {
    public Preview extract(String html, String bodySelector) {
        if (html == null || bodySelector == null || bodySelector.isBlank()) {
            throw new IllegalArgumentException("HTML and explicit body selector are required");
        }
        Document document = Jsoup.parse(html);
        var roots = document.select(bodySelector);
        if (roots.size() != 1) throw new IllegalArgumentException("Body selector must match exactly one element");
        Element root = roots.first().clone();
        root.select("script,style,noscript,svg,canvas,iframe,nav,footer,header,form,figure,figcaption,img,video,audio,object,embed").remove();
        List<String> lines = root.wholeText().replace('\u00a0', ' ')
                .replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::strip).map(line -> line.replaceAll("[\\t\\x0B\\f ]+", " "))
                .filter(line -> !line.isBlank()).toList();
        String text = String.join("\n", lines);
        if (text.isBlank()) throw new IllegalArgumentException("Article body is empty");
        return new Preview(text, SourceHashing.sha256(text), lines.size(),
                "CONTENT_REVIEW_REQUIRED");
    }

    public record Preview(String normalizedText, String contentSha256, int lineCount, String status) {}
}
