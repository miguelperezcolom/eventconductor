package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class AssetEntity {

    @Id
    String id;

    String name;

    String path;

    String url;
}
