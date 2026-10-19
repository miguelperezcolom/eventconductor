package io.mateu.workflow.test;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JUnit side of {@link EventConductorTest}: before each test, the definitions it names are
 * imported (once per context), the engine clock is the test clock and the mocks are fresh; after it,
 * the engine clock is the system clock again. Resolves {@link WorkflowTestRuntime} parameters.
 */
public class EventConductorExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /** Context + location pairs already imported, so a cached context does not import twice. */
    private static final Set<String> DEPLOYED = ConcurrentHashMap.newKeySet();

    @Override
    public void beforeEach(ExtensionContext context) {
        var runtime = runtime(context);
        var annotation = AnnotatedElementUtils.findMergedAnnotation(context.getRequiredTestClass(), EventConductorTest.class);
        if (annotation != null) {
            for (var location : annotation.definitions()) {
                if (DEPLOYED.add(System.identityHashCode(runtime) + "|" + location)) {
                    runtime.deploy(location);
                }
            }
        }
        runtime.beginTest();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        runtime(context).endTest();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == WorkflowTestRuntime.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return runtime(extensionContext);
    }

    private static WorkflowTestRuntime runtime(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context).getBean(WorkflowTestRuntime.class);
    }
}
