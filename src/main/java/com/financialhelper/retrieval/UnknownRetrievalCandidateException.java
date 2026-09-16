package com.financialhelper.retrieval;

public class UnknownRetrievalCandidateException extends RuntimeException {
    public UnknownRetrievalCandidateException() {
        super("candidate id was not issued by the requested search");
    }
}
