package com.financialhelper.source;

/** Parses one explicitly supported official acquisition representation. */
public interface SourceDocumentParser {

    boolean supports(SourceAcquisitionType acquisitionType);

    SourceIngestionData.Parsed parse(
            SourceIngestionData.Snapshot source,
            SourceIngestionData.Fetched fetched
    );
}
