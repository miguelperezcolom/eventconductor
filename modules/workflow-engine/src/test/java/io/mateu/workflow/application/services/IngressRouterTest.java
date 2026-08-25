package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.application.out.ShardRegistry;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where a new process is placed on a shard: idempotency first (an existing key returns to its shard),
 * then round-robin for new keys, and local upstream for children, non-sharded, and the empty-registry
 * fail-safe.
 *
 * <p>These cover the fallback shape — no placement store configured, so rule 1 is answered from the
 * read model. {@link IngressRouterPlacementTest} covers the shape a sharded fleet should actually run
 * in, where the claim answers it.
 */
@ExtendWith(MockitoExtension.class)
class IngressRouterTest {

    @Mock UpstreamEventPublisher upstreamEventPublisher;
    @Mock IngressPublisher ingressPublisher;
    @Mock ProcessIndexRepository processIndexRepository;
    @Mock ShardRegistry shardRegistry;

    private IngressRouter router(boolean sharding) {
        return new IngressRouter(upstreamEventPublisher, ingressPublisher,
                processIndexRepository, shardRegistry, noPlacementStore(), sharding);
    }

    /** No placement store configured — the pre-existing behaviour these tests were written for. */
    static ObjectProvider<ProcessPlacementRepository> noPlacementStore() {
        return placementStore(null);
    }

    static ObjectProvider<ProcessPlacementRepository> placementStore(ProcessPlacementRepository store) {
        return new ObjectProvider<>() {
            @Override
            public ProcessPlacementRepository getIfAvailable() {
                return store;
            }

            @Override
            public ProcessPlacementRepository getObject() {
                return store;
            }

            @Override
            public ProcessPlacementRepository getObject(Object... args) {
                return store;
            }

            @Override
            public ProcessPlacementRepository getIfUnique() {
                return store;
            }
        };
    }

    private ProcessCreationRequested creation(String businessKey) {
        return new ProcessCreationRequested("wd-1", businessKey, List.of());
    }

    private ProcessIndexRow rowOnShard(String shardId) {
        return new ProcessIndexRow("p-1", "bk-1", "a process", "wd-1", 1, "RUNNING", 0,
                LocalDateTime.now(), null, null, LocalDateTime.now(), shardId);
    }

    @Test
    void notShardedGoesToLocalUpstream() {
        var creation = creation("bk-1");
        router(false).route(creation);

        verify(upstreamEventPublisher).publish(creation);
        verifyNoInteractions(ingressPublisher);
    }

    @Test
    void aChildStaysOnItsParentsShardViaLocalUpstream() {
        // parentStepExecutionId != null → spawned by a PROCESS step, must not be re-placed.
        var child = new ProcessCreationRequested("wd-1", "bk-child", List.of(), "parent-step-1");
        router(true).route(child);

        verify(upstreamEventPublisher).publish(child);
        verifyNoInteractions(ingressPublisher);
    }

    @Test
    void anExistingBusinessKeyReturnsToItsOwnShard() {
        when(processIndexRepository.findByBusinessKey("bk-1")).thenReturn(Optional.of(rowOnShard("shard-B")));
        var creation = creation("bk-1");

        router(true).route(creation);

        verify(ingressPublisher).publishToShard(creation, "shard-B");
        verifyNoInteractions(upstreamEventPublisher);
    }

    @Test
    void newKeysAreSpreadRoundRobinAcrossActiveShards() {
        when(processIndexRepository.findByBusinessKey(any())).thenReturn(Optional.empty());
        when(shardRegistry.activeShards()).thenReturn(List.of("s0", "s1"));

        var router = router(true);
        var a = creation("bk-a");
        var b = creation("bk-b");
        var c = creation("bk-c");
        router.route(a);
        router.route(b);
        router.route(c);

        verify(ingressPublisher).publishToShard(a, "s0");
        verify(ingressPublisher).publishToShard(b, "s1");
        verify(ingressPublisher).publishToShard(c, "s0");
    }

    @Test
    void anEmptyRegistryFallsBackToLocalUpstreamRatherThanDroppingTheRequest() {
        lenient().when(processIndexRepository.findByBusinessKey(any())).thenReturn(Optional.empty());
        when(shardRegistry.activeShards()).thenReturn(List.of());
        var creation = creation("bk-1");

        router(true).route(creation);

        verify(upstreamEventPublisher).publish(creation);
        verifyNoInteractions(ingressPublisher);
    }
}
