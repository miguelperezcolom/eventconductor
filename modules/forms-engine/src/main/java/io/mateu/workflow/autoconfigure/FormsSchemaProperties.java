package io.mateu.workflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How the forms engine applies its own schema. See {@link FormsSchemaAutoConfiguration}. */
@ConfigurationProperties(prefix = "forms.schema")
public class FormsSchemaProperties {

    /**
     * Whether the engine brings its own schema up to date at startup. Turn it off only when
     * something else owns the forms tables — a DBA-applied schema, or a host application that has
     * copied the migrations into its own Flyway.
     */
    private boolean enabled = true;

    /**
     * The table the forms engine records its own migration history in. Distinct from the workflow
     * and rules engines' tables because all three are routinely pointed at one database and each
     * carries a different set of migrations under the same version numbers.
     */
    private String table = "eventconductor_schema_history_forms";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }
}
