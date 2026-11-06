package com.financialhelper.ai.information;

import com.financialhelper.ai.AiOutputContractException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Only exact, currently captured source text can pass as an information statement. */
@Component
public class InformationExplanationValidator {
    public List<InformationEvidenceSnapshot.Evidence> validate(
            InformationExplanationAiResult output, InformationEvidenceSnapshot snapshot) {
        if (output == null || output.selections() == null || snapshot == null
                || snapshot.evidence().isEmpty() || output.selections().isEmpty()) {
            throw new AiOutputContractException("information evidence selection is unavailable");
        }
        Map<UUID, InformationEvidenceSnapshot.Evidence> allowed = snapshot.evidence().stream()
                .collect(Collectors.toMap(InformationEvidenceSnapshot.Evidence::supportChunkId, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (var selection : output.selections()) {
            if (selection == null || selection.supportChunkId() == null
                    || !seen.add(selection.supportChunkId())) {
                throw new AiOutputContractException("information evidence selection is invalid");
            }
            var evidence = allowed.get(selection.supportChunkId());
            if (evidence == null || !evidence.supportText().equals(selection.quotedSupportText())
                    || !ids(evidence.conditions()).equals(selection.conditionChunkIds())
                    || !ids(evidence.exceptions()).equals(selection.exceptionChunkIds())) {
                throw new AiOutputContractException("information output differs from captured evidence");
            }
        }
        // The server, not the model, supplies all metadata, conditions and exceptions.
        return output.selections().stream().map(item -> allowed.get(item.supportChunkId())).toList();
    }

    private static List<UUID> ids(List<InformationEvidenceSnapshot.RelatedText> related) {
        return related.stream().map(InformationEvidenceSnapshot.RelatedText::chunkId).toList();
    }
}
