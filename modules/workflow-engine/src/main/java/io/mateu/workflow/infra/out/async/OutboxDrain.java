package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Drains a bounded batch of pending outbox messages, claiming them with row locks so any number
 * of pods can relay at once without electing a leader between them.
 *
 * <p>Each pass is one transaction: claim ids (skipping what other pods hold), deliver, mark the
 * batch Sent, commit. Delivery happens <b>before</b> the rows are marked, and a crash anywhere in
 * between rolls the transaction back and releases the locks, so another pod redelivers — the
 * at-least-once contract the relay always had, unchanged. Marking first would lose messages.
 *
 * <p>That ordering is necessary and was not sufficient. It only means anything if a delivery that
 * fails <em>says so</em>, and for a long time it could not: the send was asynchronous and its
 * return value discarded, so a broker that was down still produced a row marked Sent. During a
 * ninety-second broker outage, 71 of 642 912 messages were lost that way. The publisher now
 * throws when the broker refuses, which lands in the catch below and leaves the row Pending —
 * and the applications configure the producer synchronously so that refusal is knowable at all.
 *
 * <p>The batch is bounded for a reason beyond memory: the claim holds row locks for as long as
 * the transaction runs, so the batch size is really a bound on how long other pods can be kept
 * from those rows.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class OutboxDrain {

    final OutboxMessageEntityRepository outboxMessageEntityRepository;

    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;
    final TransactionTemplate transactionTemplate;

    /**
     * What one pass achieved. {@code claimed} says whether there may be more waiting — a full
     * batch means keep going — and {@code settled} says whether the pass actually moved anything
     * out of Pending. A caller that loops on a full batch must stop when nothing settles, or a
     * batch of permanently failing messages becomes a hot loop.
     */
    public record Result(int claimed, int settled) {}

    /**
     * Claims and delivers up to {@code batchSize} messages. A message whose payload cannot be
     * deserialized can never succeed, so it is parked as Error rather than retried forever; one
     * whose delivery throws is left Pending for the next pass.
     */
    public Result drain(int batchSize, Consumer<DomainEvent> deliver) {
        var result = transactionTemplate.execute(status -> {
            var ids = claim(batchSize);
            if (ids.isEmpty()) {
                return new Result(0, 0);
            }
            var sent = new ArrayList<OutboxMessageEntity>();
            var poisoned = new ArrayList<OutboxMessageEntity>();
            for (var message : outboxMessageEntityRepository.findAllById(ids)) {
                final DomainEvent payload;
                try {
                    payload = (DomainEvent) pojoFromJson(message.getPayload(),
                            OutboxMessages.messageClass(message.getMessageType()));
                } catch (Exception e) {
                    log.error("Outbox message {} cannot be deserialized, marking as Error",
                            message.getId(), e);
                    poisoned.add(message);
                    continue;
                }
                try {
                    log.debug("Relaying outbox message {}", message.getId());
                    // Delivered as a continuation of the trace that produced the event, not of the
                    // relay pass that happens to be draining it. Without this the send belongs to
                    // no trace at all and the consumer on the other side starts a fresh one, so a
                    // process reads as a series of unrelated traces rather than one.
                    workflowTracing.continuing(message.getTraceParent(), "outbox relay",
                            () -> deliver.accept(payload));
                    sent.add(message);
                } catch (Exception e) {
                    // Left Pending: the next pass picks it up again.
                    log.error("Failed to relay outbox message {}, will retry next cycle",
                            message.getId(), e);
                }
            }
            sent.forEach(message -> message.setStatus(OutboxMessageStatus.Sent.name()));
            poisoned.forEach(message -> message.setStatus(OutboxMessageStatus.Error.name()));
            if (!sent.isEmpty() || !poisoned.isEmpty()) {
                var touched = new ArrayList<>(sent);
                touched.addAll(poisoned);
                outboxMessageEntityRepository.saveAll(touched);
            }
            return new Result(ids.size(), sent.size() + poisoned.size());
        });
        return result == null ? new Result(0, 0) : result;
    }

    private List<String> claim(int batchSize) {
        return jdbcTemplate.query(
                dbLockDialect.claimPendingOutboxSql(),
                ps -> ps.setInt(1, batchSize),
                (rs, rowNum) -> rs.getString(1));
    }
}
