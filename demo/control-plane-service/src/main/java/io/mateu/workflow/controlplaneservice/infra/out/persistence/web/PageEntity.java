package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class PageEntity {

    @Id
    String id;

    String name;

    String path;

    String websiteId;
}
