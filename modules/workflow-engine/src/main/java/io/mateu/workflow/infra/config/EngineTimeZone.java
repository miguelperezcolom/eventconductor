package io.mateu.workflow.infra.config;

import io.mateu.workflow.time.Moments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * {@code workflow.time.zone}: the zone in which a date without one is read — an {@code untilVariable}
 * holding {@code 2026-08-01}, a moment that names no {@code zone}. Unset, it is the JVM's zone, which
 * is what such dates have always meant. Set it explicitly where the servers' zone is not the
 * business's (a cluster in UTC booking hotels in Madrid).
 */
@Component
public class EngineTimeZone {

    public EngineTimeZone(@Value("${workflow.time.zone:}") String zone) {
        Moments.setDefaultZone(zone == null || zone.isBlank() ? null : ZoneId.of(zone.trim()));
    }
}
