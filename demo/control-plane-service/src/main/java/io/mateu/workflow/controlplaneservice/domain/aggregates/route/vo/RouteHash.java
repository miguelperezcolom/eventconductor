package io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo;


public record RouteHash(String hash) {

    public RouteHash {
        if (hash == null) throw new IllegalArgumentException("hash is required");
    }
}
