package io.mateu.workflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How the workflow engine applies its own schema. See {@link WorkflowSchemaAutoConfiguration}. */
@ConfigurationProperties(prefix = "workflow.schema")
public class WorkflowSchemaProperties {

    /**
     * Whether the engine brings its own schema up to date at startup. Turn it off only when
     * something else owns the engine's tables — a DBA-applied schema, or a host application that
     * has copied the migrations into its own Flyway. Off means the engine runs on whatever schema
     * it finds, including one without its indexes.
     */
    private boolean enabled = true;

    /**
     * The table the engine records its own migration history in. Deliberately not Flyway's default
     * {@code flyway_schema_history}: a host application embedding the engine has its own migrations
     * numbered from V1 in that table, and two independent numbering spaces cannot share one history.
     */
    private String table = "eventconductor_schema_history";

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
