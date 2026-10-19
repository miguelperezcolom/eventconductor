package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.ProcessUpdateScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Process updates on the durable path (embedded + JPA on H2): the same scenarios as {@link UpdatesE2eTest}. */
class UpdatesJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessUpdateService service;
    @Autowired ProcessUpdateRepository updates;
    @Autowired ProcessLockService locks;

    ProcessUpdateScenario scenario;

    @BeforeEach
    void scenario() {
        scenario = new ProcessUpdateScenario(service, processUpstreamEventUseCase, processRepository,
                stepExecutionRepository, updates, locks, worker);
    }

    @Test
    void anAcceptedUpdateAnswersWithItsResult() throws Exception {
        scenario.anAcceptedUpdateAnswersWithItsResult();
    }

    @Test
    void aPayloadTheSchemaRefusesIsA400() throws Exception {
        scenario.aPayloadTheSchemaRefusesIsA400();
    }

    @Test
    void aGuardThatSaysNoIsA422() throws Exception {
        scenario.aGuardThatSaysNoIsA422();
    }

    @Test
    void aProcessThatIsNotAcceptingIsA409() throws Exception {
        scenario.aProcessThatIsNotAcceptingIsA409();
    }

    @Test
    void severalUpdatesApplyInOrder() throws Exception {
        scenario.severalUpdatesApplyInOrder();
    }

    @Test
    void concurrentUpdatesAreSerialized() throws Exception {
        scenario.concurrentUpdatesAreSerialized();
    }

    @Test
    void aRetryWithTheKeyIsTheSameUpdate() throws Exception {
        scenario.aRetryWithTheKeyIsTheSameUpdate();
    }

    @Test
    void theDeadlineIsA202AndTheUpdateCompletesAfter() throws Exception {
        scenario.theDeadlineIsA202AndTheUpdateCompletesAfter();
    }

    @Test
    void updateWithStartStartsOrUpdates() throws Exception {
        scenario.updateWithStartStartsOrUpdates();
    }

    @Test
    void compensationSeesUpdatesAndAFailedProcessRefusesThem() throws Exception {
        scenario.compensationSeesUpdatesAndAFailedProcessRefusesThem();
    }

    @Test
    void anUpdateCanResumeTheFlow() throws Exception {
        scenario.anUpdateCanResumeTheFlow();
    }

    @Test
    void failuresUnknownsAndMasking() throws Exception {
        scenario.failuresUnknownsAndMasking();
    }
}
