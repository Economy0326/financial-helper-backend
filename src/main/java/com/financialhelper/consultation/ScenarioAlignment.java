package com.financialhelper.consultation;

/** Situation의 명시적 신호와 사용자가 선택한 scenario의 관계다. */
public enum ScenarioAlignment {
    SELECTED_SCENARIO_MATCH,
    SUPPORTED_SCENARIO_MISMATCH,
    UNSUPPORTED_SCOPE,
    NEEDS_CLARIFICATION
}
