package io.mateu.sample;

import io.mateu.sample.greetings.GreetV1Input;
import io.mateu.sample.greetings.GreetV1Output;
import io.mateu.sample.greetings.GreetV1Task;
import io.mateu.workflow.worker.api.TaskContext;
import org.springframework.stereotype.Component;

/**
 * The whole worker: the business logic of the {@code greet} task, typed to its contract. Everything
 * else — the Kafka binding, variable marshalling, replies, cancellation — is the worker-kafka
 * runtime; {@link GreetV1Task}, {@link GreetV1Input} and {@link GreetV1Output} are generated from
 * {@code greet.ectask} into {@code target/generated-sources} and never edited.
 *
 * <p>Return normally to complete the step; throw {@link GreetV1Task.EmptyName} — a generated
 * subclass of {@code TaskFailure}, one per declared error — to fail it with that business code.
 */
@Component
public class GreetHandler implements GreetV1Task {

    @Override
    public GreetV1Output handle(GreetV1Input input, TaskContext context) {
        if (input.name() == null || input.name().isBlank()) {
            throw new GreetV1Task.EmptyName("a name is required");
        }
        return new GreetV1Output("Hello, " + input.name() + "!");
    }
}
