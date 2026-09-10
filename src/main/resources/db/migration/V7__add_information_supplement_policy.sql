ALTER TABLE consultation
    ADD COLUMN information_supplement_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE consultation
    ADD CONSTRAINT ck_consultation_information_supplement_count
        CHECK (
            information_supplement_count >= 0
            AND information_supplement_count <= 1
        );