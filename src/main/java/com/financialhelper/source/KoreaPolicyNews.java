package com.financialhelper.source;

import org.jsoup.nodes.Document;
import java.net.URI;

/** Narrow boundary for FSC-labelled policy-news pages, not a general korea.kr crawler. */
final class KoreaPolicyNews {
    static final String BODY = ".article_body .view_cont";
    static boolean allowed(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "www.korea.kr".equalsIgnoreCase(uri.getHost())
                && "/news/policyNewsView.do".equals(uri.getPath())
                && uri.getRawQuery() != null && uri.getRawQuery().matches("newsId=[0-9]{9}")
                && uri.getFragment() == null && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    static String select(Document doc) {
        var info = doc.select(".article_head .info");
        boolean agency = info.size() == 1 && info.first().select("a").stream()
                .anyMatch(a -> a.text().replaceAll("\\s+", "").matches("금융위원회(?:부처별뉴스이동)?"));
        if (!agency || doc.select(BODY).size() != 1)
            throw new IllegalArgumentException("Policy-news agency or body could not be verified");
        return BODY;
    }
    private KoreaPolicyNews() {}
}
