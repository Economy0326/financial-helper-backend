package com.financialhelper.ai.understanding;

import com.financialhelper.consultation.Consultation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

// @Entity -> DB 데이터를 Java 객체처럼 다루기 위한 클래스
@Entity
@Table(
        name = "case_understanding",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_case_understanding_consultation_revision",
                        columnNames = {
                                "consultation_id",
                                "case_input_revision"
                        }
                )
        }
)
public class CaseUnderstanding {

    @Id
    // id는 JPA가 자동 생성
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 여러 CaseUnderstanding이 하나의 Consultation에 속할 수 있음
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    // Entity끼리의 객체 참조가 DB에서는 어떤 FK 컬럼으로 연결되는지 알려주는 설정
    @JoinColumn(
            name = "consultation_id",
            nullable = false
    )
    private Consultation consultation;

    @Column(
            name = "case_input_revision",
            nullable = false
    )
    private long caseInputRevision;

    @Column(
            name = "model",
            nullable = false,
            length = 100
    )
    private String model;

    @Column(
            name = "result_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String resultJson;

    @Column(
            name = "generated_at",
            nullable = false
    )
    private OffsetDateTime generatedAt;

    // protected => 일반 코드 생성 x, JPA 같은 프레임워크는 접근 가능
    protected CaseUnderstanding() {
    }

    public CaseUnderstanding(
            Consultation consultation,
            long caseInputRevision,
            String model,
            String resultJson,
            OffsetDateTime generatedAt
    ) {
        this.consultation =
                consultation;

        this.caseInputRevision =
                caseInputRevision;

        this.model =
                model;

        this.resultJson =
                resultJson;

        this.generatedAt =
                generatedAt;
    }

    public UUID getId() {
        return id;
    }

    public Consultation getConsultation() {
        return consultation;
    }

    public long getCaseInputRevision() {
        return caseInputRevision;
    }

    public String getModel() {
        return model;
    }

    public String getResultJson() {
        return resultJson;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }
}