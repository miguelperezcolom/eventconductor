package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.fluent.OnCustomEventTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * Edits a form definition through the standalone {@code <eventconductor-form-editor>} web component
 * (shipped by this module, served same-origin from {@code META-INF/resources}), replacing mateu's
 * built-in {@code FormEditor}. The component is embedded the same way the workflow graph is embedded
 * in the engine UI — an {@link Element} with the custom tag, an {@code import} of the ESM bundle and
 * a {@code value} attribute carrying the form JSON — and its {@code value-changed} custom event is
 * bound to the {@link #save} action so edits round-trip back to the {@link FormRepository}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
@PageWidth(PageWidthStyle.FULL_WIDTH)
public class FormEditor implements TriggersSupplier {

    /** Custom element that edits the form. Shipped by this module. */
    private static final String EDITOR_TAG = "eventconductor-form-editor";
    /** Same-origin URL of the component's ESM bundle (served from META-INF/resources). */
    private static final String EDITOR_MODULE = "/eventconductor/form-editor.js";
    /** DOM event the component fires on every edit, carrying the new form JSON in {@code detail.value}. */
    private static final String VALUE_CHANGED = "value-changed";

    final FormRepository repository;

    @Hidden
    String formId;

    @Label("")
    Element editor;

    public FormEditor load(String workflowId) {
        this.formId = workflowId;
        var def = repository.findById(workflowId).orElseThrow();
        // Rendered through mateu's Element/import mechanism: mateu dynamically imports the module the
        // first time the tag is used, and the custom element upgrades in place. `value` carries the
        // form JSON; edits are pushed back through the value-changed event wired below in triggers().
        var attrs = new java.util.HashMap<String, String>();
        attrs.put("import", EDITOR_MODULE);
        attrs.put("value", toJson(def));
        // Bind the component's value-changed event to the save action, so an edit persists. The
        // trigger below (OnCustomEventTrigger) is the subscription; this is the DOM-level wiring.
        this.editor = Element.builder()
                .name(EDITOR_TAG)
                .attributes(attrs)
                .on(Map.of(VALUE_CHANGED, "save"))
                .content("")
                .style("display: block; width: 100%; height: 72vh; min-height: 480px;")
                .build();
        return this;
    }

    /**
     * Persists an edit. Reads the new form JSON the component emitted (the {@code value} it carries
     * after the edit), deserialises it to a {@link Form} — the same shape {@code form-schema.json}
     * and the {@code Form} record use — and saves it, keeping the id stable across edits.
     */
    @Action(id = "save")
    public FormEditor save(HttpRequest httpRequest) {
        var json = emittedValue(httpRequest);
        if (json == null || json.isBlank()) {
            return this;
        }
        try {
            var edited = pojoFromJson(json, Form.class);
            // Keep the identity of the form being edited: the editor round-trips the id, but fall
            // back to the loaded formId so a save can never fork a new form by losing it.
            var id = edited.id() != null && !edited.id().isBlank() ? edited.id() : formId;
            var toSave = new Form(id, edited.name(), edited.description(), edited.fields());
            repository.save(toSave);
        } catch (RuntimeException e) {
            // A transient invalid document (mid-edit) must not blow up the view; the next valid
            // value-changed will persist. Logged so a genuinely broken payload is visible.
            log.warn("Ignoring an invalid form edit for form {}: {}", formId, e.getMessage());
        }
        return this;
    }

    @Override
    public java.util.List<Trigger> triggers(HttpRequest httpRequest) {
        // Subscribe the save action to the component's value-changed event, so every edit is
        // persisted. SELF (the default) scopes the subscription to this view's component.
        return java.util.List.of(new OnCustomEventTrigger("save", VALUE_CHANGED));
    }

    /**
     * The form JSON the component emitted with its value-changed event. The component sends the new
     * value as its {@code value} in the component state; fall back to the event detail's value if a
     * renderer surfaces it there instead.
     */
    private static String emittedValue(HttpRequest httpRequest) {
        if (httpRequest == null || httpRequest.runActionRq() == null) {
            return null;
        }
        var state = httpRequest.runActionRq().componentState();
        if (state == null) {
            return null;
        }
        var value = state.get("value");
        if (value instanceof String s) {
            return s;
        }
        var detail = state.get("detail");
        if (detail instanceof Map<?, ?> map && map.get("value") instanceof String s) {
            return s;
        }
        return null;
    }
}
