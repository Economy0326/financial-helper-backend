package com.financialhelper.source;

import org.jsoup.nodes.Document;

/** FSC board body boundary; never falls back to the entire page. */
final class FscHtmlBodySelector {
    private static final String SELECTOR = "div.body > div.cont";
    static String select(Document document) {
        if (document.select(SELECTOR).size() != 1) {
            throw new IllegalArgumentException("FSC body selector must match exactly one element");
        }
        return SELECTOR;
    }
    private FscHtmlBodySelector() {}
}
