CREATE TABLE source_text_registration (
    source_document_id UUID PRIMARY KEY REFERENCES source_document(id) ON DELETE RESTRICT,
    submitted_html_sha256 VARCHAR(64) NOT NULL CHECK (submitted_html_sha256 ~ '^[0-9a-f]{64}$'),
    extraction_selector VARCHAR(500) NOT NULL,
    extraction_version VARCHAR(64) NOT NULL,
    usage_review_id UUID NOT NULL,
    document_version INTEGER NOT NULL,
    stored_text_sha256 VARCHAR(64) NOT NULL CHECK (stored_text_sha256 ~ '^[0-9a-f]{64}$'),
    registered_by UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (usage_review_id,source_document_id,document_version,stored_text_sha256)
        REFERENCES source_document_usage_review(id,source_document_id,document_version,raw_sha256) ON DELETE RESTRICT
);

CREATE FUNCTION prohibit_source_text_registration_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'text registration provenance is immutable';
END;
$$;
CREATE TRIGGER source_text_registration_immutable BEFORE UPDATE OR DELETE ON source_text_registration
    FOR EACH ROW EXECUTE FUNCTION prohibit_source_text_registration_mutation();
