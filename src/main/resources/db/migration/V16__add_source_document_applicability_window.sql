ALTER TABLE source_document
    ADD COLUMN applicability_start_date DATE,
    ADD COLUMN applicability_end_date DATE;

ALTER TABLE source_document
    ADD CONSTRAINT ck_source_document_applicability_window
        CHECK (
            applicability_start_date IS NULL
            OR applicability_end_date IS NULL
            OR applicability_end_date >= applicability_start_date
        );
