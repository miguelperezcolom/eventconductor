package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class ReleaseEntity {

    @Id
    String id;

    String name;

    LocalDateTime timestamp;

    String websiteId;

    String environmentId;

}
