package io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo;


public record TierName(String name) {

    public TierName {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("maskedUrl is required");
    }
}
