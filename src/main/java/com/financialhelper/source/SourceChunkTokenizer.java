package com.financialhelper.source;

/**
 * semantic runtime이 indexing에 사용하는 것과 같은 tokenizer로 document token을 센다.
 * runtime을 사용할 수 없으면 구현은 실패해야 하며 문자 길이를 token 수 fallback으로 쓰지 않는다.
 */
public interface SourceChunkTokenizer {

    int countDocumentTokens(String text);

    default String identifier() {
        return "unknown";
    }

    default String revision() {
        return "unknown";
    }
}
