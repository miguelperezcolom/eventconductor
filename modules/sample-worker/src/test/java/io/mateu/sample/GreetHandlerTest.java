package io.mateu.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mateu.sample.greetings.GreetV1Input;
import io.mateu.sample.greetings.GreetV1Task;
import org.junit.jupiter.api.Test;

/**
 * The handler is a plain object typed to the generated contract, so it is tested as one — no broker,
 * no Spring. The runtime that carries it (binding, replies, cancellation) is tested in worker-kafka.
 */
class GreetHandlerTest {

    private final GreetHandler handler = new GreetHandler();

    @Test
    void greets_by_name() throws Exception {
        var output = handler.handle(new GreetV1Input("Ada"), null);
        assertThat(output.message()).isEqualTo("Hello, Ada!");
    }

    @Test
    void a_blank_name_is_the_declared_business_error() {
        assertThatThrownBy(() -> handler.handle(new GreetV1Input(" "), null))
                .isInstanceOf(GreetV1Task.EmptyName.class)
                .satisfies(e -> assertThat(((GreetV1Task.EmptyName) e).code()).isEqualTo("EMPTY_NAME"));
    }
}
