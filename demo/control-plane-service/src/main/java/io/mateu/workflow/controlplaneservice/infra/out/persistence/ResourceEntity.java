package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class ResourceEntity {

    @Id
    String id;

    String name;

    String path;

    byte[] content;

    int statusCode;

    LocalDateTime lastUpdated;

    long size;

    long milliseconds;

}
