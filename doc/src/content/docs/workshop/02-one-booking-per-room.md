---
title: "2 · One booking per room"
description: A forEach over the rooms, each room a small flow of its own written in line, labelled in the console and opened into lanes.
---

The hotel's system wants **one booking per room**. The booking has a list of rooms, so the process has to fan out: one branch per room, each one a small flow of its own, and then carry on when they are all done.

## The definition

`02-rooms-booking.yaml`:

```yaml
id: rooms-booking
name: Book every room
steps:
  - id: start
    type: START
    name: Booking received

  - id: register-booking
    type: ACTION
    name: Register the booking
    preconditionStepId: start

  - id: rooms
    type: PROCESS
    name: Book every room
    preconditionStepId: register-booking
    outputVariables: [roomBookingId]       # what each room hands back
    forEach:
      items: rooms                         # a JSON array variable
      as: room                             # each branch sees its room as `room`
      label: "'Room ' + room.number"       # how each branch is named in the console
      output: roomBookings                 # collected, in room order
      outputElement: roomBookingId
    steps:                                 # the branch, written in line
      - id: start
        type: START
        name: Start
      - id: create-room-booking
        type: ACTION
        name: Book the room
        preconditionStepId: start
      - id: end
        type: END
        name: End
        preconditionStepId: create-room-booking

  - id: notify-guest
    type: ACTION
    name: Tell the guest
    preconditionStepId: rooms

  - id: done
    type: END
    name: Done
    preconditionStepId: notify-guest
```

Three ideas in one step:

- **`forEach`** runs the step once per element of `rooms` — in parallel by default (`mode: sequential` for one at a time). `output` collects what each branch produced, in the order of the rooms.
- **`type: PROCESS` with `steps:`** — the branch is a whole flow, written **in line**. Here it has a single action, but it can be as long as you need: book, confirm, notify. Each branch runs as a child process of its own, with its own variables, so branches never step on each other.
- **`label`** names each branch for people: `Room 101` rather than `iteration 0`.

`notify-guest` waits for `rooms`, so it runs **once**, after every room is booked — the forEach is a fork and a join in one step.

The worker for one room:

```java
public record CreateRoomBookingInput(Room room) {}
public record CreateRoomBookingOutput(String roomBookingId) {}

@Bean
TaskRegistration<CreateRoomBookingInput, CreateRoomBookingOutput> createRoomBooking() {
    return new TaskRegistration<>("create-room-booking", 1, "hotel",
            CreateRoomBookingInput.class, CreateRoomBookingOutput.class,
            (input, context) -> new CreateRoomBookingOutput("RB-" + input.room().number()));
}
```

## Run it

```bash
scripts/start.sh rooms-booking BOOKING-2 '{"guest": "Alice", "rooms": [
  {"number": "101", "type": "double"}, {"number": "102", "type": "double"},
  {"number": "103", "type": "double"}]}'
```

In the Diagram, the `rooms` step shows **3/3**: three branches, three completed. **Double-click** it to open it into its lanes — one per room, with the steps of its flow and their state:

![Double-click the forEach to open one lane per room](/workshop/02-rooms-lanes.gif)

![The lanes: Room 101, 102 and 103, each with its own step](/workshop/02-rooms-lanes.jpg)

Double-click a lane's header to open that room's own run; double-click the node again to fold it.

## Check

- [ ] `rooms` shows **3/3** and opens into three lanes named *Room 101*, *Room 102*, *Room 103*.
- [ ] The Variables tab has `roomBookings`, one id per room, in room order.
- [ ] `notify-guest` ran once, after the three rooms.
