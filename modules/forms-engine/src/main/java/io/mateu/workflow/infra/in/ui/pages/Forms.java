package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * The form definitions this engine imported.
 *
 * <p>The detail carries no "Graph editor" button any more. {@link FormEditor} is still there and
 * still works — nothing about it was removed — but a definition is imported from git, so editing
 * one on screen produces a form that the next import silently replaces. Offering the button on the
 * screen that shows an imported definition invites exactly that, and the loss is quiet: the edit
 * simply is not there the next time anyone looks.
 */
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
public class Forms extends AutoCrud<Form> {

    final FormRepository formRepository;

    @Override
    public CrudStore<Form> store() {
        return formRepository;
    }
}
