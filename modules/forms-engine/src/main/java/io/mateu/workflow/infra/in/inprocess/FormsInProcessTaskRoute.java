package io.mateu.workflow.infra.in.inprocess;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskCommand;
import io.mateu.workflow.application.usecases.canceltask.CancelTaskUseCase;
import io.mateu.workflow.application.usecases.createtask.CreateTaskCommand;
import io.mateu.workflow.application.usecases.createtask.CreateTaskUseCase;
import io.mateu.workflow.application.usecases.escalatetask.EscalateTaskUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.inprocess.InProcessTaskRoute;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * The forms engine's side of a USER_TASK when the workflow engine is embedded in the same application
 * ({@code workflow.mode=embedded}): the in-process counterpart of {@code FormsEngineKafkaConsumerConfig}.
 * The engine offers it every downstream event before its {@code EmbeddedTaskExecutor}, and it takes
 *
 * <ul>
 *   <li>the {@code complete-form} task of a USER_TASK that names a form — the task is created and shows
 *       up in the inbox; the person's submission answers the engine;</li>
 *   <li>the escalation of such a task;</li>
 *   <li>the cancellation of a step this engine holds a task for (a timeout, a cancelled process).</li>
 * </ul>
 *
 * Everything else is left to the application's executor. Run on the dispatching thread: creating,
 * escalating or cancelling a task never calls back into the engine.
 */
@Slf4j
public class FormsInProcessTaskRoute implements InProcessTaskRoute {

    public static final String COMPLETE_FORM = "complete-form";

    private final CreateTaskUseCase createTaskUseCase;
    private final CancelTaskUseCase cancelTaskUseCase;
    private final EscalateTaskUseCase escalateTaskUseCase;
    private final FormExecutionRepository formExecutionRepository;

    public FormsInProcessTaskRoute(CreateTaskUseCase createTaskUseCase, CancelTaskUseCase cancelTaskUseCase,
                                   EscalateTaskUseCase escalateTaskUseCase,
                                   FormExecutionRepository formExecutionRepository) {
        this.createTaskUseCase = createTaskUseCase;
        this.cancelTaskUseCase = cancelTaskUseCase;
        this.escalateTaskUseCase = escalateTaskUseCase;
        this.formExecutionRepository = formExecutionRepository;
    }

    @Override
    public boolean take(DomainEvent event, String topic) {
        if (event instanceof TaskExecutionRequested task && COMPLETE_FORM.equals(task.taskId())) {
            var variables = task.variables() == null ? List.<io.mateu.workflow.dtos.Variable>of() : task.variables();
            var formId = variables.stream()
                    .filter(variable -> "formId".equals(variable.name()))
                    .map(io.mateu.workflow.dtos.Variable::value)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
            if (formId == null) {
                // A USER_TASK without a form: nothing to show, the application answers it.
                return false;
            }
            log.info("Creating the {} task of step {} of process {}", formId, task.stepId(), task.processId());
            createTaskUseCase.handle(new CreateTaskCommand(task.taskExecutionId(), task.processId(),
                    task.workflowDefinitionId(), task.stepId(), formId,
                    variables.stream().map(variable -> new Variable(variable.name(), variable.value())).toList()));
            return true;
        }
        if (event instanceof HumanTaskEscalationRequested escalation) {
            escalateTaskUseCase.handle(escalation);
            return true;
        }
        if (event instanceof TaskCancellationRequested cancellation
                && !formExecutionRepository.findByStepExecutionId(cancellation.taskId()).isEmpty()) {
            cancelTaskUseCase.handle(new CancelTaskCommand(cancellation.taskId()));
            return true;
        }
        return false;
    }
}
