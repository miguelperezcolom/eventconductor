package io.mateu.workflow.booking.domain.aggregates.booking;


import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingStatus;
import io.mateu.workflow.booking.domain.aggregates.booking.vo.BookingId;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Name;
import io.mateu.workflow.booking.domain.aggregates.shared.vo.Time;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Booking extends AggregateRoot {

    BookingId id;

    Name leadName;

    Time created;

    BookingStatus status;


    public static Booking of(BookingId id,
                             Name leadName) {
        Booking p = new Booking();
        p.id = id;
        p.leadName = leadName;
        p.created = new Time(LocalDateTime.now());
        p.status =  BookingStatus.Pending;
        return p;
    }

    public void update(Name leadName) {
        this.leadName = leadName;
    }

    public void changeStatus(BookingStatus status) {
        this.status = status;
    }
}
