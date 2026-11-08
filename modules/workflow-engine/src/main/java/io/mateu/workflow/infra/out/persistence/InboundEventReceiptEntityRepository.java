package io.mateu.workflow.infra.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface InboundEventReceiptEntityRepository extends JpaRepository<InboundEventReceiptEntity, String> {

    List<InboundEventReceiptEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<InboundEventReceiptEntity> findByWorkflowIdOrderByReceivedAtDesc(String workflowId, Pageable pageable);

    List<InboundEventReceiptEntity> findByFlowKeyOrderByReceivedAtDesc(String flowKey, Pageable pageable);

    List<InboundEventReceiptEntity> findByWorkflowIdAndFlowKeyOrderByReceivedAtDesc(String workflowId, String flowKey, Pageable pageable);

    @Modifying
    @Query("delete from InboundEventReceiptEntity r where r.receivedAt < :cutoff")
    int deleteReceivedBefore(LocalDateTime cutoff);
}
