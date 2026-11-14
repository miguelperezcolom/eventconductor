package io.mateu.workflow.test;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.AnnotationConsumer;
import org.junit.jupiter.params.support.ParameterDeclarations;

import java.util.stream.Stream;

/** The {@link ScenarioSource} provider: one {@link Scenario} argument per scenario found. */
public final class ScenarioArgumentsProvider implements ArgumentsProvider, AnnotationConsumer<ScenarioSource> {

    private String[] locations = new String[0];

    @Override
    public void accept(ScenarioSource source) {
        this.locations = source.value();
    }

    @Override
    public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
        return Scenario.load(locations).stream().map(Arguments::of);
    }
}
