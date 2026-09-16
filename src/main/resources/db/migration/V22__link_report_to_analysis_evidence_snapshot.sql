ALTER TABLE consultation_report
    ADD COLUMN analysis_evidence_snapshot_id UUID;

ALTER TABLE consultation_report
    ADD CONSTRAINT fk_consultation_report_evidence_snapshot
    FOREIGN KEY (analysis_evidence_snapshot_id)
    REFERENCES analysis_evidence_snapshot (id);

CREATE INDEX ix_consultation_report_evidence_snapshot
    ON consultation_report (analysis_evidence_snapshot_id);
