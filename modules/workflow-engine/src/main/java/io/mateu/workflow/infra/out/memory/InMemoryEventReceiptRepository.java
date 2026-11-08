package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Inbound event receipts for memory persistence: one JVM, a map is the whole store. */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventReceiptRepository implements EventReceiptRepository {

    private final Map<String, EventReceipt> receipts = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String receiptKey) {
        return receipts.containsKey(receiptKey);
    }

    @Override
    public void save(EventReceipt receipt) {
        receipts.putIfAbsent(receipt.receiptKey(), receipt);
    }

    @Override
    public List<EventReceipt> recent(int limit, String workflowId) {
        return receipts.values().stream()
                .filter(r -> workflowId == null || workflowId.equals(r.workflowId()))
                .sorted(Comparator.comparing(EventReceipt::receivedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<EventReceipt> recentOfFlowKey(int limit, String workflowId, String flowKey) {
        return receipts.values().stream()
                .filter(r -> flowKey.equals(r.flowKey()))
                .filter(r -> workflowId == null || workflowId.equals(r.workflowId()))
                .sorted(Comparator.comparing(EventReceipt::receivedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public int purgeReceivedBefore(Instant cutoff) {
        int before = receipts.size();
        receipts.values().removeIf(r -> r.receivedAt().isBefore(cutoff));
        return before - receipts.size();
    }
}
