package io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo;


public record TierParallelThreads(int parallelThreads) {

    public TierParallelThreads {
        if (parallelThreads <= 0) throw new IllegalArgumentException("threads must be greater than 0");
    }
}
