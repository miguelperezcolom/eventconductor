package io.mateu.workflow.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Mounts the engines' own UI — the workflow, forms and rules pages and the beans behind them
 * ({@code io.mateu.workflow.infra.in.ui}) — in an application started with
 * {@code @WorkflowEmbeddedApplication}, {@code @FormsEmbeddedApplication} or
 * {@code @RulesEmbeddedApplication}, which leave that layer out so a headless or in-memory
 * deployment never loads it.
 *
 * <p>Put it beside the embedded annotation when the application shows the UI — it mounts
 * {@code WorkflowMenu}, {@code FormsMenu} or {@code RulesMenu} in its own {@code @UI}:
 *
 * <pre>
 * &#64;WorkflowEmbeddedApplication
 * &#64;EnableWorkflowUi
 * public class MyApp { … }
 * </pre>
 *
 * <p>Before Mateu 3.0-alpha.409 nothing asked for this: Mateu's generated configuration scanned
 * all of {@code io.mateu}, and swept the engines' UI in with it whether the application wanted it
 * or not. Mateu now scans only its own packages. An application annotated
 * {@code @SpringBootApplication} in a package under {@code io.mateu.workflow} (the standalone apps)
 * already scans the UI and does not need this.
 *
 * <p>It decides what is mounted, nothing more. {@code workflow.ui.enabled=false} still removes the
 * UI however it got there (the switch drops every bean of that package, this scan's included), and
 * whatever is mounted sits behind the consoles' access rule ({@code ConsoleAccess}): Spring
 * Security, or {@code workflow.message-api.allow-anonymous=true}, or {@code 403}.
 *
 * <p>It is an import, not a {@code @ComponentScan} on the application class, because a
 * {@code @ComponentScan} declared there replaces the scans the embedded annotation carries instead
 * of adding to them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(WorkflowUiConfiguration.class)
public @interface EnableWorkflowUi {
}
