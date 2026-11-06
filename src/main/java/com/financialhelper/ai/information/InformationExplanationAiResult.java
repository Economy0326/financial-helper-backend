package com.financialhelper.ai.information;

import java.util.List;
import java.util.UUID;

/** Extractive AI selection. Free-form financial claims are deliberately absent. */
public record InformationExplanationAiResult(List<Selection> selections) {
    public record Selection(UUID supportChunkId, String quotedSupportText,
                            List<UUID> conditionChunkIds, List<UUID> exceptionChunkIds) {}
}
