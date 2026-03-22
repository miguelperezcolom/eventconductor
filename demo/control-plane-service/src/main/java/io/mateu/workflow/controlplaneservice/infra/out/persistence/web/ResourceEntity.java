package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ResourceEntity {

    @Id
    String id;

    String name;

    byte[] bytes;

    String hash;
}
