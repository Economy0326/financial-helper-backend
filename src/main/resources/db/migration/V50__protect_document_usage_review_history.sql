-- Existing decisions remain unchanged; subsequent decisions can identify their predecessor.
ALTER TABLE source_document_usage_review ADD COLUMN previous_review_id UUID;
ALTER TABLE source_document_usage_review ADD CONSTRAINT uk_usage_review_identity
    UNIQUE (id, source_document_id, document_version, raw_sha256);
ALTER TABLE source_document_usage_review ADD CONSTRAINT fk_usage_review_predecessor
    FOREIGN KEY (previous_review_id, source_document_id, document_version, raw_sha256)
    REFERENCES source_document_usage_review(id, source_document_id, document_version, raw_sha256) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uk_usage_review_successor ON source_document_usage_review(previous_review_id)
    WHERE previous_review_id IS NOT NULL;

CREATE FUNCTION prohibit_source_usage_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'source document usage review history is immutable';
END;
$$;
CREATE TRIGGER source_usage_review_immutable BEFORE UPDATE OR DELETE ON source_document_usage_review
    FOR EACH ROW EXECUTE FUNCTION prohibit_source_usage_review_mutation();
