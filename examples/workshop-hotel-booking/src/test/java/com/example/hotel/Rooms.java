package com.example.hotel;

/** Test data: the rooms of a booking, as the process carries them (a JSON array). */
final class Rooms {

    static final String THREE_DOUBLES = """
            [{"number": "101", "type": "double"},
             {"number": "102", "type": "double"},
             {"number": "103", "type": "double"}]""";

    static final String WITH_A_PENTHOUSE = """
            [{"number": "101", "type": "double"},
             {"number": "102", "type": "double"},
             {"number": "900", "type": "penthouse"}]""";

    private Rooms() {
    }
}
