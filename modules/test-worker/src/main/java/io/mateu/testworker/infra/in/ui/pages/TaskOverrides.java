package io.mateu.testworker.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.testworker.application.TaskOverrideStore;
import io.mateu.testworker.domain.TaskOverride;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The canned replies, edited by hand.
 *
 * <p>A plain Crud over {@link TaskOverride}: the record is the form, so what you see here is
 * exactly what the resolver reads. Nothing is mapped or renamed in between, which is deliberate —
 * a simulator whose UI describes something subtly different from what it does is a simulator that
 * will eventually be trusted about the wrong thing.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
public class TaskOverrides extends AutoCrud<TaskOverride> {

    final TaskOverrideStore taskOverrideStore;

    @Override
    public CrudStore<TaskOverride> store() {
        return taskOverrideStore;
    }
}
