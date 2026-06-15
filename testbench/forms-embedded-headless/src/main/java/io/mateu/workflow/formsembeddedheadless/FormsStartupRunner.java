package io.mateu.workflow.formsembeddedheadless;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.Variable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class FormsStartupRunner implements ApplicationRunner {

    final FormRepository formRepository;
    final CreateTaskUseCase createTaskUseCase;
    final FormExecutionRepository formExecutionRepository;

    @Override
    public void run(ApplicationArguments args) {
        // Create a sample form in memory
        String formId = UUID.randomUUID().toString();
        formRepository.save(new Form(
                formId,
                "Contact Form",
                "A simple contact form example",
                List.of()
        ));
        log.info("Created form: {}", formId);

        // Create a FormExecution (task) for that form
        createTaskUseCase.handle(new CreateTaskCommand(
                UUID.randomUUID().toString(),
                "process-1",
                "workflow-1",
                "step-1",
                formId,
                List.of(new Variable("name", "Alice"))
        ));

        // Log the created execution
        formExecutionRepository.findAll().forEach(execution ->
                log.info("FormExecution created: id={}, formId={}, status={}",
                        execution.id(), execution.formId(), execution.status()));
    }

}
