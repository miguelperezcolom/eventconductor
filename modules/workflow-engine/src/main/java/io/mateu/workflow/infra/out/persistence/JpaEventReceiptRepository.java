package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Inbound event receipts in the database, shared by every pod consuming the destinations. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class JpaEventReceiptRepository implements EventReceiptRepository {

    /** As long as the indexed column: a longer key is kept, and searched, by its first characters. */
    private static final int FLOW_KEY_LENGTH = 255;

    final InboundEventReceiptEntityRepository repository;

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String receiptKey) {
        return repository.existsById(receiptKey);
    }

    @Override
    public void save(EventReceipt receipt) {
        if (repository.existsById(receipt.receiptKey())) {
            return;
        }
        try {
            repository.saveAndFlush(new InboundEventReceiptEntity(receipt.receiptKey(), cut(receipt.receiver(), 1024),
                    receipt.kind().name(), cut(receipt.destination(), 255), cut(receipt.eventType(), 512),
                    cut(receipt.eventSource(), 1024), cut(receipt.eventId(), 512), receipt.outcome().name(),
                    cut(receipt.workflowId(), 255), cut(receipt.triggerId(), 160), cut(receipt.businessKey(), 1024),
                    cut(receipt.messageName(), 255), cut(receipt.correlationKey(), 1024), cut(receipt.detail(), 2000),
                    cut(receipt.flowKey(), FLOW_KEY_LENGTH), LocalDateTime.ofInstant(receipt.receivedAt(), ZoneOffset.UTC)));
        } catch (DataIntegrityViolationException alreadyThere) {
            // Another pod (or a concurrent duplicate) recorded it first: the receipt exists, which is all
            // this was for.
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventReceipt> recent(int limit, String workflowId) {
        var page = PageRequest.of(0, Math.max(1, limit));
        var rows = workflowId == null ? repository.findAllByOrderByReceivedAtDesc(page)
                : repository.findByWorkflowIdOrderByReceivedAtDesc(workflowId, page);
        return rows.stream().map(JpaEventReceiptRepository::toReceipt).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventReceipt> recentOfFlowKey(int limit, String workflowId, String flowKey) {
        var page = PageRequest.of(0, Math.max(1, limit));
        var key = cut(flowKey, FLOW_KEY_LENGTH);
        var rows = workflowId == null ? repository.findByFlowKeyOrderByReceivedAtDesc(key, page)
                : repository.findByWorkflowIdAndFlowKeyOrderByReceivedAtDesc(workflowId, key, page);
        return rows.stream().map(JpaEventReceiptRepository::toReceipt).toList();
    }

    @Override
    @Transactional
    public int purgeReceivedBefore(Instant cutoff) {
        return repository.deleteReceivedBefore(LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    private static EventReceipt toReceipt(InboundEventReceiptEntity row) {
        return new EventReceipt(row.getReceiptKey(), row.getReceiver(), EventReceipt.Kind.valueOf(row.getReceiverKind()),
                row.getDestination(), row.getEventType(), row.getEventSource(), row.getEventId(),
                EventReceipt.Outcome.valueOf(row.getOutcome()), row.getWorkflowId(), row.getTriggerId(),
                row.getBusinessKey(), row.getMessageName(), row.getCorrelationKey(), row.getDetail(),
                row.getFlowKey(), row.getReceivedAt().toInstant(ZoneOffset.UTC));
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
