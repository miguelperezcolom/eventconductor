package io.mateu.workflow.booking.infra.in.ui;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@UI("/_booking")
@Title("")
@FavIcon("/images/riu.svg")
@PageTitle("Booking")
@Logo("/images/riu.svg")
@Style(StyleConstants.CONTAINER)
@RequiredArgsConstructor
@Service
public class BookingHome {

    @Menu
    BookingMenu booking;

}
