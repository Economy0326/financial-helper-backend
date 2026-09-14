-- PostgreSQL keyword foundation.  The simple configuration is deliberately
-- language-neutral; this is not a Korean morphology analyzer or BM25.
ALTER TABLE source_chunk
    ADD COLUMN keyword_search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(body, '')), 'A')
        || setweight(to_tsvector('simple', coalesce(parent_section, '')), 'B')
        || setweight(to_tsvector('simple', coalesce(article_reference, '')), 'B')
        || setweight(to_tsvector('simple', coalesce(page_reference, '')), 'C')
        || setweight(to_tsvector('simple', coalesce(locator, '')), 'C')
        || setweight(
            to_tsvector('simple', coalesce(representation_metadata_json, '')),
            'D'
        )
    ) STORED;

CREATE INDEX idx_source_chunk_keyword_search_vector_approved
    ON source_chunk USING GIN (keyword_search_vector)
    WHERE review_status = 'APPROVED';
