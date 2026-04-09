package io.mateu.workflow.application.usecases.process.start;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

record Pair<K, V>(K key, V value) {}

@Service
@RequiredArgsConstructor
public class StartProcessUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;

    public void handle(StartProcessCommand command) {

    }

}
