package com.financialhelper.source;

public interface SourceContentFetcher {

    SourceIngestionData.Fetched fetch(
            SourceIngestionData.Snapshot source
    );
}