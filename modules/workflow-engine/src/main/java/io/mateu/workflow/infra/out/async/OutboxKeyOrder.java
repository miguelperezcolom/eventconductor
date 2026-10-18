package io.mateu.workflow.infra.out.async;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Which of the rows a relay pass has claimed it may publish <b>now</b> without overtaking an older
 * event of the same process that another pod holds, or that a failed send left Pending.
 *
 * <p>The claim itself stays the plain {@code FOR UPDATE SKIP LOCKED} over the oldest pending rows,
 * so it costs one index range whatever the backlog looks like. Every pod relays, so that claim alone
 * let pod B take event 2 of a process while pod A held event 1 — the lock B skipped, a batch limit
 * falling between the two, or A's send of event 1 failing and leaving it Pending for a retry. This
 * is the second half: one non-locking read of the pending rows of the keys this pass claimed, and,
 * per key, the claimed rows that form an unbroken prefix of that key's pending run are sendable. The
 * first pending row of a key that this pass does <em>not</em> hold stops the key there; what it
 * claimed after it stays Pending, its lock released at commit, and goes in a later pass once the
 * older row has been published.
 *
 * <p>Why a read after the claim and not a predicate inside it: a correlated {@code NOT EXISTS} in the
 * locking query was measured to turn quadratic whenever the planner believed there were few pending
 * rows — a fresh table, or a backlog that arrived after the last {@code ANALYZE} — 6 s locally and
 * 17 s on CI for ten thousand rows, against 6 ms for the plain claim. The read here is one {@code IN}
 * per thousand keys and is linear however it is planned.
 *
 * <p>The read sees committed state. A row another pod has claimed but not yet sent is still Pending
 * there, so it holds its successors back; one it has published and committed is Sent, and its
 * successor may go — after the broker acknowledged the older one. A row without a key
 * ({@code partition_key} NULL: an unkeyed event, or a row written before V33) is ordered against
 * nothing, as it always was.
 *
 * <p>There is always progress: the pod holding the oldest pending row of a key holds that key's
 * head, and a head is never held back.
 */
public final class OutboxKeyOrder {

    /** Below every database's limit on an {@code IN} list (Oracle's is 1000 expressions). */
    public static final int MAX_KEYS_PER_STATEMENT = 1000;

    private OutboxKeyOrder() {
    }

    /** A pending outbox row as far as ordering is concerned. */
    public record Row(String id, String key, LocalDateTime timestamp) {
    }

    private static final Comparator<Row> ORDER = Comparator
            .comparing(Row::timestamp, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Row::id);

    /**
     * The pending rows of {@code keyCount} keys up to a timestamp: {@code id, partition_key,
     * timestamp}, parameters the keys and then the newest claimed timestamp. Nothing newer than what
     * the pass claimed can stand in front of it, so the bound only trims the read. No lock, no
     * limit, no correlated subquery — portable to every supported database as written.
     */
    public static String pendingOfKeysSql(int keyCount) {
        if (keyCount < 1) {
            throw new IllegalArgumentException("at least one partition key is required");
        }
        return "SELECT id, partition_key, timestamp FROM outbox_message_entity WHERE status = 'Pending' "
                + "AND partition_key IN (" + String.join(", ", Collections.nCopies(keyCount, "?")) + ") "
                + "AND timestamp <= ?";
    }

    /**
     * The ids of {@code claimed} that may be published now, given every pending row of their keys
     * (as {@link #pendingOfKeysSql} reads them — the claimed rows themselves included).
     */
    public static Set<String> sendable(Collection<Row> claimed, Collection<Row> pendingOfTheirKeys) {
        var held = new HashSet<String>();
        var sendable = new HashSet<String>();
        for (var row : claimed) {
            held.add(row.id());
            if (row.key() == null) {
                sendable.add(row.id());
            }
        }
        var byKey = new LinkedHashMap<String, List<Row>>();
        for (var row : pendingOfTheirKeys) {
            if (row.key() != null) {
                byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
            }
        }
        for (var run : byKey.values()) {
            run.sort(ORDER);
            for (var row : run) {
                if (!held.contains(row.id())) {
                    break; // older than whatever this pass holds next, and not ours: the key stops here
                }
                sendable.add(row.id());
            }
        }
        return sendable;
    }
}
