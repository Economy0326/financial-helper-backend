/* case_input_revision => Category / Situation의 현재 버전*/
/* follow_up_answer_revision => 추가 질문 답변의 현재 버전 */
ALTER TABLE consultation
    ADD COLUMN case_input_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN follow_up_answer_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE consultation
    ADD CONSTRAINT ck_consultation_case_input_revision_non_negative
        CHECK (case_input_revision >= 0),
    ADD CONSTRAINT ck_consultation_follow_up_answer_revision_non_negative
        CHECK (follow_up_answer_revision >= 0);