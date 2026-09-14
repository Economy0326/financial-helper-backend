package com.financialhelper.source;

public class RetrievalGenerationDefinitionConflictException
        extends IllegalStateException {

    public RetrievalGenerationDefinitionConflictException() {
        super(
                "A retrieval generation already exists for the generation key with a different definition"
        );
    }
}
