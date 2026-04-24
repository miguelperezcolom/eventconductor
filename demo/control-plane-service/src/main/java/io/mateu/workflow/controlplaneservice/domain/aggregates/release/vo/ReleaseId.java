package io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo;

import java.util.Objects;

public record ReleaseId(Long id) {

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ReleaseId releaseId = (ReleaseId) o;
        return Objects.equals(id, releaseId.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
