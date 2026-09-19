package io.mateu.workflow.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task-contract model, pinned at the JSON boundary the engine, the Maven plugin and the code
 * generator all parse it through: the fields map, the type tokens are lowercase, declaration order
 * survives, {@code array} carries its {@code items}, and a contract round-trips unchanged.
 */
class TaskContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String JSON = """
            {
              "id": "confirm-booking",
              "version": 2,
              "group": "booking",
              "topic": "booking",
              "description": "Confirm a reservation",
              "input": {
                "bookingId": {"type": "string", "required": true, "description": "the reservation"},
                "nights": {"type": "integer"},
                "guests": {"type": "array", "items": {"type": "string"}}
              },
              "output": {
                "confirmationCode": {"type": "string", "required": true}
              },
              "errors": [
                {"code": "SOLD_OUT", "description": "no rooms left"},
                {"code": "ALREADY_CONFIRMED"}
              ]
            }
            """;

    @Test
    void parses_every_field() throws Exception {
        TaskContract c = mapper.readValue(JSON, TaskContract.class);

        assertThat(c.id()).isEqualTo("confirm-booking");
        assertThat(c.version()).isEqualTo(2);
        assertThat(c.group()).isEqualTo("booking");
        assertThat(c.topic()).isEqualTo("booking");
        assertThat(c.ref()).isEqualTo("confirm-booking@2");

        assertThat(c.input().get("bookingId").type()).isEqualTo(TaskType.STRING);
        assertThat(c.input().get("bookingId").required()).isTrue();
        assertThat(c.input().get("nights").type()).isEqualTo(TaskType.INTEGER);
        assertThat(c.input().get("nights").required()).isFalse();
        assertThat(c.input().get("guests").type()).isEqualTo(TaskType.ARRAY);
        assertThat(c.input().get("guests").items().type()).isEqualTo(TaskType.STRING);

        assertThat(c.output().get("confirmationCode").required()).isTrue();
        assertThat(c.errors()).extracting(TaskError::code).containsExactly("SOLD_OUT", "ALREADY_CONFIRMED");
    }

    @Test
    void input_keeps_declaration_order() throws Exception {
        TaskContract c = mapper.readValue(JSON, TaskContract.class);
        assertThat(c.input().keySet()).containsExactly("bookingId", "nights", "guests");
    }

    @Test
    void type_tokens_are_lowercase_and_round_trip() throws Exception {
        assertThat(mapper.writeValueAsString(TaskType.DATETIME)).isEqualTo("\"datetime\"");
        assertThat(mapper.readValue("\"boolean\"", TaskType.class)).isEqualTo(TaskType.BOOLEAN);
    }

    @Test
    void a_contract_round_trips_unchanged() throws Exception {
        TaskContract parsed = mapper.readValue(JSON, TaskContract.class);
        TaskContract reparsed = mapper.readValue(mapper.writeValueAsString(parsed), TaskContract.class);
        assertThat(reparsed).isEqualTo(parsed);
    }

    @Test
    void absent_collections_default_to_empty() {
        TaskContract c = new TaskContract("t", 1, "g", null, null, null, null, null);
        assertThat(c.input()).isEmpty();
        assertThat(c.output()).isEmpty();
        assertThat(c.errors()).isEmpty();
    }
}
