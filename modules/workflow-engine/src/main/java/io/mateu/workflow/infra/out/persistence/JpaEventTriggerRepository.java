package io.mateu.workflow.infra.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code events.yaml} triggers in the database, so every pod matches inbound events against the same set. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaEventTriggerRepository implements EventTriggerRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final EventTriggerEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<EventFileTrigger> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(EventTriggerEntity::getSourceKey)
                        .thenComparingInt(EventTriggerEntity::getEntryIndex))
                .map(JpaEventTriggerRepository::map)
                .toList();
    }

    /** Delete and insert in one transaction: a match never sees a source half replaced. */
    @Override
    @Transactional
    public void replace(String source, List<EventFileTrigger> triggers) {
        repository.deleteBySourceKey(source);
        repository.flush();
        if (triggers == null || triggers.isEmpty()) {
            return;
        }
        repository.saveAll(triggers.stream().map(t -> new EventTriggerEntity(
                id(source, t.index()), source, t.index(), t.workflowId(), t.trigger().id(), t.trigger().destination(),
                t.trigger().type(), t.trigger().filter(), t.trigger().businessKey(),
                variablesToJson(t.trigger().variables()))).toList());
    }

    private static EventFileTrigger map(EventTriggerEntity row) {
        return new EventFileTrigger(row.getSourceKey(), row.getEntryIndex(), row.getWorkflowId(),
                new EventTrigger(row.getTriggerId(), row.getDestination(), row.getEventType(), row.getFilterExpression(),
                        row.getBusinessKey(), variablesFromJson(row.getVariablesJson())));
    }

    static String id(String source, int index) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((source + "#" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String variablesToJson(Map<String, Object> variables) {
        if (variables == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise trigger variables", e);
        }
    }

    private static Map<String, Object> variablesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
