package com.financialhelper.source;

public class SourceChunkDefinitionConflictException
        extends IllegalStateException {

    public SourceChunkDefinitionConflictException() {
        super(
                "A source chunk already exists for the document, chunk configuration, and sequence with a different definition"
        );
    }
}
