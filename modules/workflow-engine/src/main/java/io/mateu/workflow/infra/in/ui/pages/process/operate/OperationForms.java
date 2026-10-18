package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.data.Dialog;
import io.mateu.uidl.data.EmbeddedView;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.UICommand;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the manual-operation dialogs of the process detail share: how they open, how they answer, and
 * the event the detail listens to so it repaints once one was carried out.
 */
public final class OperationForms {

    /** Dispatched (on the document) when a dialog's operation was accepted; the process detail refreshes on it. */
    public static final String OPERATED_EVENT = "ec-process-operated";

    private OperationForms() {
    }

    /** A modal dialog holding {@code form} — a view model of its own, whose buttons call back into it. */
    public static Dialog dialog(String title, Object form) {
        return Dialog.builder()
                .headerTitle(title)
                .width("36rem")
                .closeButtonOnHeader(true)
                .content(new EmbeddedView(form))
                .build();
    }

    /** The operation was accepted: say so, close the dialog, and let the detail repaint. */
    public static List<Object> accepted(String what) {
        return List.of(Message.success(what + " — carried out by the node that owns the process."),
                UICommand.closeModal(OPERATED_EVENT));
    }

    /** The operation was refused: say why, and leave the dialog open to correct it. */
    public static Message refused(RuntimeException e) {
        return Message.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /**
     * The caller as resolved at this request. The dialogs are plain view models — Mateu refuses a Spring
     * bean it did not route to — so what they need is looked up when their action runs.
     */
    public static AuthorizationContext caller() {
        try {
            var resolver = io.mateu.uidl.di.MateuBeanProvider.getBean(CallerResolver.class);
            return resolver == null ? null : resolver.current();
        } catch (RuntimeException noResolver) {
            return null;
        }
    }

    /** An application service, for a dialog's action. */
    public static <T> T bean(Class<T> type) {
        return io.mateu.uidl.di.MateuBeanProvider.getBean(type);
    }

    /** Rows of a master-detail list, without the empty ones a form leaves behind. */
    static List<Variable> variables(List<Variable> rows) {
        var variables = new ArrayList<Variable>();
        if (rows != null) {
            rows.stream().filter(v -> v != null && v.name() != null && !v.name().isBlank()).forEach(variables::add);
        }
        return variables;
    }

    /** A comma- or whitespace-separated list of names. */
    static List<String> names(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
