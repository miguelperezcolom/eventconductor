package com.example.impl;

import com.example.greetings.GreetV1Input;
import com.example.greetings.GreetV1Output;
import com.example.greetings.GreetV1Task;
import io.mateu.workflow.worker.api.TaskContext;
import org.springframework.stereotype.Component;

/**
 * The only thing a worker developer writes: the business logic, typed to the generated contract.
 * {@link GreetV1Task}, {@link GreetV1Input} and {@link GreetV1Output} are generated from
 * {@code greet.ectask} into {@code target/generated-sources} — never edited.
 */
@Component
public class GreetV1Impl implements GreetV1Task {

    @Override
    public GreetV1Output handle(GreetV1Input input, TaskContext context) {
        return new GreetV1Output("Hello, " + input.name() + "!");
    }
}
