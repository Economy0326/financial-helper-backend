package com.financialhelper.source;

/** 명시적으로 지원하는 공식 획득 representation 하나를 parse한다. */
public interface SourceDocumentParser {

    boolean supports(SourceAcquisitionType acquisitionType);

    SourceIngestionData.Parsed parse(
            SourceIngestionData.Snapshot source,
            SourceIngestionData.Fetched fetched
    );
}
