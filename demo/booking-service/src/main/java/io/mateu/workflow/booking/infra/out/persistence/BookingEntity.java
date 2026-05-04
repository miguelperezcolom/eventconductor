package io.mateu.workflow.booking.infra.out.persistence;


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
public class BookingEntity {

    @Id
    String id;

    String leadName;

    LocalDateTime created;

    String status;

}
