package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexQueryService;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.DefinitionAnalytics;
import io.mateu.workflow.application.services.ProcessAnalyticsService.DurationStats;
import io.mateu.workflow.application.services.ProcessAnalyticsService.StepAnalytics;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase.ImportWorkflowDefinitionsResult;
import io.mateu.workflow.application.usecases.lifecycle.PauseWorkflowUseCase;
import io.mateu.workflow.application.usecases.lifecycle.ResumeWorkflowUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.input.InputLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What every workflow tool answers an agent, and what it does: the reads (processes, details, logs,
 * the read model, analytics), the operations (pause/resume a process or a definition, the import),
 * the refusals an agent is told about (an unknown process or definition, an oversized message), and
 * that Spring AI registers every published tool — the names themselves are pinned by
 * {@link WorkflowMcpToolSurfaceTest}.
 */
class WorkflowMcpToolsTest {

    static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 1, 9, 0);
    static final LocalDateTime FINISHED = LocalDateTime.of(2026, 10, 1, 9, 5);

    final ProcessRepository processes = mock(ProcessRepository.class);
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final LogMessageRepository logs = mock(LogMessageRepository.class);
    final PauseWorkflowUseCase pauseWorkflow = mock(PauseWorkflowUseCase.class);
    final ResumeWorkflowUseCase resumeWorkflow = mock(ResumeWorkflowUseCase.class);
    final ImportWorkflowDefinitionsFromGitUseCase gitImport = mock(ImportWorkflowDefinitionsFromGitUseCase.class);
    final CommandDispatcher commands = mock(CommandDispatcher.class);
    final MessageDispatcher messages = mock(MessageDispatcher.class);
    final ProcessAnalyticsService analytics = mock(ProcessAnalyticsService.class);
    final ProcessIndexQueryService index = mock(ProcessIndexQueryService.class);
    WorkflowMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new WorkflowMcpTools(processes, steps, logs, mock(RetryProcessUseCase.class),
                mock(PauseProcessUseCase.class), mock(ResumeProcessUseCase.class), pauseWorkflow, resumeWorkflow,
                gitImport, commands, messages, analytics, index);
    }

    private static Process process(String id, String businessKey, List<io.mateu.workflow.domain.aggregates.Variable> variables) {
        return Process.builder().id(id).name("Booking").businessKey(businessKey).status(ProcessStatus.RUNNING)
                .completionPercentage(40).created(CREATED).variables(variables).build();
    }

    // ── Registration ─────────────────────────────────────────────────────────────────────────────

    @Test
    void springAiRegistersEveryPublishedTool() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        var declared = Arrays.stream(WorkflowMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class))
                .map(java.lang.reflect.Method::getName).toList();
        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrderElementsOf(declared)
                .hasSize(16);
        assertThat(tools.getSystemContext()).contains("retryProcess", "restartProcess", "sendMessage", "findBottleneck");
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    /** Through the callback, as the MCP server calls it: the refusal reaches the agent with its reason. */
    @Test
    void anUnknownProcessReachesTheAgentAsAToolErrorThatSaysWhich() {
        when(processes.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> callback("getProcessDetails").call("{\"processId\": \"nope\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Process not found: nope");
    }

    @Test
    void aToolCalledThroughItsCallbackAnswersJson() {
        when(processes.findAll()).thenReturn(List.of(process("p-1", "bk-1", List.of())));

        assertThat(callback("listProcesses").call("{}"))
                .contains("\"id\":\"p-1\"", "\"businessKey\":\"bk-1\"", "\"status\":\"RUNNING\"", "\"completionPct\":40");
    }

    // ── Reads ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void listsEveryProcessWithItsDatesOrNullWhenUnset() {
        var finished = process("p-1", "bk-1", List.of()).withStatus(ProcessStatus.COMPLETED).withFinished(FINISHED);
        var neverStarted = process("p-2", "bk-2", List.of()).withCreated(null);
        when(processes.findAll()).thenReturn(List.of(finished, neverStarted));

        assertThat(tools.listProcesses()).containsExactly(
                new WorkflowMcpTools.ProcessSummary("p-1", "Booking", "bk-1", "COMPLETED", 40,
                        "2026-10-01T09:00", "2026-10-01T09:05"),
                new WorkflowMcpTools.ProcessSummary("p-2", "Booking", "bk-2", "RUNNING", 40, null, null));
    }

    @Test
    void theDetailsCarryTheVariablesAsNameEqualsValueAndEveryStep() {
        var process = process("p-1", "bk-1", List.of(new io.mateu.workflow.domain.aggregates.Variable("amount", "12"), new io.mateu.workflow.domain.aggregates.Variable("currency", "EUR")));
        when(processes.findById("p-1")).thenReturn(Optional.of(process));
        when(steps.findByProcess(process)).thenReturn(List.of(
                StepExecution.builder().id("se-1").stepId("reserve").status(StepExecutionStatus.COMPLETED)
                        .workerId("w-1").startedAt(CREATED).build(),
                StepExecution.builder().id("se-2").stepId("charge").status(StepExecutionStatus.CREATED).build()));

        var detail = tools.getProcessDetails("p-1");

        assertThat(detail.id()).isEqualTo("p-1");
        assertThat(detail.status()).isEqualTo("RUNNING");
        assertThat(detail.completionPct()).isEqualTo(40);
        assertThat(detail.variables()).containsExactly("amount=12", "currency=EUR");
        assertThat(detail.steps()).containsExactly(
                new WorkflowMcpTools.StepSummary("se-1", "reserve", "COMPLETED", "w-1", "2026-10-01T09:00"),
                new WorkflowMcpTools.StepSummary("se-2", "charge", "CREATED", null, null));
    }

    @Test
    void aProcessWithoutVariablesHasAnEmptyList() {
        var process = process("p-1", "bk-1", null);
        when(processes.findById("p-1")).thenReturn(Optional.of(process));

        assertThat(tools.getProcessDetails("p-1").variables()).isEmpty();
    }

    @Test
    void anUnknownProcessIsRefusedWithItsId() {
        when(processes.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.getProcessDetails("nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Process not found: nope");
    }

    @Test
    void findsAProcessByItsBusinessKeyOrSaysThereIsNone() {
        var process = process("p-1", "bk-1", List.of());
        when(processes.findByBusinessKey("bk-1")).thenReturn(Optional.of(process));
        when(processes.findById("p-1")).thenReturn(Optional.of(process));
        when(processes.findByBusinessKey("ghost")).thenReturn(Optional.empty());

        assertThat(tools.findProcessByBusinessKey("bk-1").id()).isEqualTo("p-1");
        assertThatThrownBy(() -> tools.findProcessByBusinessKey("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No process found for business key: ghost");
    }

    /** The logs of one process, read by process — never by scanning the whole log table. */
    @Test
    void theLogsOfAProcessAreReadByProcess() {
        when(logs.findByProcessId("p-1")).thenReturn(List.of(
                new LogMessage("l-1", CREATED, "p-1", "se-1", "INFO", "reserved", "w-1"),
                new LogMessage("l-2", null, "p-1", null, "ERROR", "card declined", null)));

        assertThat(tools.getProcessLogs("p-1")).containsExactly(
                new WorkflowMcpTools.LogEntry("2026-10-01T09:00", "se-1", "INFO", "reserved", "w-1"),
                new WorkflowMcpTools.LogEntry(null, null, "ERROR", "card declined", null));
        verify(logs, never()).findAll();
    }

    private static ProcessIndexRow row(String id, LocalDateTime started) {
        return new ProcessIndexRow(id, "bk-" + id, "Booking", "booking", 1, "RUNNING", 50,
                CREATED, started, null, CREATED, "shard-0");
    }

    @Test
    void theInFlightProcessesComeFromTheReadModel_allOrOneDefinitions() {
        when(index.findInFlight()).thenReturn(List.of(row("p-1", CREATED), row("p-2", null)));
        when(index.findInFlightByDefinition("booking")).thenReturn(List.of(row("p-1", CREATED)));

        assertThat(tools.listInFlightProcesses(null)).containsExactly(
                new WorkflowMcpTools.ProcessIndexEntry("p-1", "bk-p-1", "booking", "RUNNING", 50,
                        "2026-10-01T09:00", "2026-10-01T09:00", null, "shard-0"),
                new WorkflowMcpTools.ProcessIndexEntry("p-2", "bk-p-2", "booking", "RUNNING", 50,
                        "2026-10-01T09:00", null, null, "shard-0"));
        assertThat(tools.listInFlightProcesses(" ")).hasSize(2);
        assertThat(tools.listInFlightProcesses("booking")).extracting(WorkflowMcpTools.ProcessIndexEntry::processId)
                .containsExactly("p-1");
        verifyNoInteractions(processes);
    }

    @Test
    void countsByStatusFromTheReadModel() {
        when(index.countByStatus()).thenReturn(Map.of("RUNNING", 3L, "ERROR", 1L));

        assertThat(tools.countProcessesByStatus()).containsExactlyInAnyOrderEntriesOf(Map.of("RUNNING", 3L, "ERROR", 1L));
        verifyNoInteractions(processes);
    }

    // ── Operations ───────────────────────────────────────────────────────────────────────────────

    @Test
    void pauseAndResumeOfAProcessArePublishedForTheNodeThatOwnsIt() {
        assertThat(tools.pauseProcess("p-1")).contains("p-1", "query the process");
        verify(commands).dispatch(new PauseProcessRequested("p-1"));

        assertThat(tools.resumeProcess("p-1")).contains("p-1", "query the process");
        verify(commands).dispatch(new ResumeProcessRequested("p-1"));
    }

    @Test
    void pauseAndResumeOfADefinitionGoToTheirUseCases() {
        assertThat(tools.pauseWorkflow("booking")).isEqualTo("Pause triggered for workflow definition booking");
        verify(pauseWorkflow).handle("booking");

        assertThat(tools.resumeWorkflow("booking")).isEqualTo("Resume triggered for workflow definition booking");
        verify(resumeWorkflow).handle("booking");
    }

    /**
     * Refused here, to the agent's face, rather than dispatched and dead-lettered out of sight: the
     * same limits as the REST endpoint.
     */
    @Test
    void anOversizedMessageIsRefusedBeforeItIsDispatched() {
        var huge = "x".repeat(InputLimits.MAX_IDENTIFIER_LENGTH + 1);

        assertThatThrownBy(() -> tools.sendMessage(huge, "bk-1", null))
                .isInstanceOf(InputLimits.InputRejectedException.class).hasMessageContaining("messageName");
        assertThatThrownBy(() -> tools.sendMessage("paid", huge, null))
                .isInstanceOf(InputLimits.InputRejectedException.class).hasMessageContaining("correlationKey");
        assertThatThrownBy(() -> tools.sendMessage("paid", "bk-1",
                Map.of("v", "y".repeat(InputLimits.MAX_VALUE_LENGTH + 1))))
                .isInstanceOf(InputLimits.InputRejectedException.class).hasMessageContaining("paid");
        verifyNoInteractions(messages);
    }

    @Test
    void theGitImportReportsWhatItImportedAndWhatFailed() {
        when(gitImport.handle()).thenReturn(new ImportWorkflowDefinitionsResult(
                List.of("booking", "refund"), List.of("bad.json: not a definition"), List.of()));

        assertThat(tools.importWorkflowDefinitionsFromGit()).isEqualTo("""
                Imported 2 definition(s):
                  - booking
                  - refund
                Errors (1):
                  - bad.json: not a definition
                """);
    }

    @Test
    void aGitImportThatFoundNothingSaysSo() {
        when(gitImport.handle()).thenReturn(new ImportWorkflowDefinitionsResult(List.of(), List.of(), List.of()));

        assertThat(tools.importWorkflowDefinitionsFromGit()).isEqualTo("No new workflow definitions found.\n");
    }

    // ── Analytics ────────────────────────────────────────────────────────────────────────────────

    private static StepAnalytics step(String id, long failed, long active, Duration avg, boolean bottleneck) {
        return new StepAnalytics(id, 10, 10 - failed - active, failed, active,
                avg == null ? new DurationStats(0, null, null) : new DurationStats(10, avg, avg.multipliedBy(2)),
                bottleneck);
    }

    private static DefinitionAnalytics definition(long total, String bottleneckStepId, List<StepAnalytics> steps) {
        var byStatus = new LinkedHashMap<ProcessStatus, Long>();
        byStatus.put(ProcessStatus.COMPLETED, 8L);
        byStatus.put(ProcessStatus.ERROR, 2L);
        var perDay = new LinkedHashMap<LocalDate, Long>();
        perDay.put(LocalDate.of(2026, 10, 1), 10L);
        return new DefinitionAnalytics("booking", "Booking", total, byStatus, 80.0, 20.0, 0.0, perDay, perDay,
                new DurationStats(10, Duration.ofMillis(1_250), Duration.ofMillis(4_049)), steps, bottleneckStepId);
    }

    @Test
    void analyticsForOneDefinitionAreReportedInSecondsWithStatusesAndDaysAsText() {
        when(analytics.analyze(eq("booking"), any())).thenReturn(Optional.of(definition(10, "charge", List.of(
                step("reserve", 0, 0, Duration.ofMillis(200), false),
                step("charge", 2, 0, Duration.ofSeconds(3), true)))));

        var report = tools.getWorkflowAnalytics("booking", 7);

        assertThat(report).hasSize(1);
        var only = report.getFirst();
        assertThat(only.windowDays()).isEqualTo(7);
        assertThat(only.instancesByStatus()).containsExactly(Map.entry("COMPLETED", 8L), Map.entry("ERROR", 2L));
        assertThat(only.processesCreatedPerDay()).containsExactly(Map.entry("2026-10-01", 10L));
        // Rounded to a tenth of a second.
        assertThat(only.avgProcessDurationSeconds()).isEqualTo(1.3);
        assertThat(only.p95ProcessDurationSeconds()).isEqualTo(4.0);
        assertThat(only.bottleneckStepId()).isEqualTo("charge");
        assertThat(only.steps()).extracting(WorkflowMcpTools.StepStats::stepId).containsExactly("reserve", "charge");
        assertThat(only.steps().get(1).bottleneck()).isTrue();
    }

    @Test
    void anEmptyNameIsEveryDefinitionAndAnUnknownOneIsRefused() {
        when(analytics.analyzeAll(any())).thenReturn(List.of(definition(10, null, List.of())));
        when(analytics.analyze(eq("ghost"), any())).thenReturn(Optional.empty());

        assertThat(tools.getWorkflowAnalytics(" ", null)).singleElement()
                .satisfies(a -> assertThat(a.windowDays()).isEqualTo(30));
        assertThatThrownBy(() -> tools.getWorkflowAnalytics("ghost", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No workflow definition found for: ghost");
        assertThatThrownBy(() -> tools.findBottleneck("ghost", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No workflow definition found for: ghost");
    }

    /** lastDays: missing or negative is 30 days, 0 is all time. */
    @Test
    void theWindowDefaultsTo30DaysAndZeroIsAllTime() {
        when(analytics.analyzeAll(any())).thenReturn(List.of());
        var windows = ArgumentCaptor.forClass(TimeWindow.class);

        tools.getWorkflowAnalytics(null, 0);
        tools.getWorkflowAnalytics(null, -5);
        tools.getWorkflowAnalytics(null, null);

        verify(analytics, org.mockito.Mockito.times(3)).analyzeAll(windows.capture());
        assertThat(windows.getAllValues().get(0)).isEqualTo(TimeWindow.all());
        for (var window : windows.getAllValues().subList(1, 3)) {
            assertThat(window.from()).isBetween(LocalDateTime.now().minusDays(30).minusMinutes(1),
                    LocalDateTime.now().minusDays(30).plusMinutes(1));
            assertThat(window.to()).isNull();
        }
    }

    @Test
    void theBottleneckReportNamesTheSlowestStepAndTheStuckAndFailingOnes() {
        when(analytics.analyze(eq("booking"), any())).thenReturn(Optional.of(definition(10, "charge", List.of(
                step("reserve", 0, 3, Duration.ofMillis(200), false),
                step("charge", 2, 0, Duration.ofSeconds(3), true)))));

        var report = tools.findBottleneck("booking", null);

        assertThat(report.workflowDefinitionId()).isEqualTo("booking");
        assertThat(report.windowDays()).isEqualTo(30);
        assertThat(report.summary()).isEqualTo("Slowest step (bottleneck): 'charge' with average 3.0 s and p95 6.0 s."
                + " Step 'reserve' has 3 instance(s) currently waiting or running."
                + " Step 'charge' has 2 failed execution(s).");
        assertThat(report.steps()).hasSize(2);
    }

    @Test
    void theBottleneckReportSaysWhenThereIsNothingToMeasure() {
        when(analytics.analyze(eq("empty"), any())).thenReturn(Optional.of(definition(0, null, List.of())));
        when(analytics.analyze(eq("unmeasured"), any())).thenReturn(Optional.of(definition(4, null, List.of(
                step("reserve", 0, 0, null, false)))));

        assertThat(tools.findBottleneck("empty", 0).summary()).isEqualTo("No process instances in the window.");
        var unmeasured = tools.findBottleneck("unmeasured", 0);
        assertThat(unmeasured.summary()).isEqualTo("No step has finished executions with measured durations yet.");
        assertThat(unmeasured.steps().getFirst().avgDurationSeconds()).isNull();
    }
}
