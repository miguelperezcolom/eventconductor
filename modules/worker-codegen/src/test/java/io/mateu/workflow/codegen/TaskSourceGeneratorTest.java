package io.mateu.workflow.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TaskSourceGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode contract(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, GeneratedJavaFile> generate(String... contracts) {
        var nodes = java.util.Arrays.stream(contracts).map(TaskSourceGeneratorTest::contract).toList();
        return new TaskSourceGenerator("com.acme.tasks").generate(nodes).javaFiles().stream()
                .collect(Collectors.toMap(GeneratedJavaFile::typeName, f -> f));
    }

    private static final String FULL = """
        {
          "id": "confirm-booking", "version": 1, "group": "booking", "topic": "booking-ops",
          "description": "Confirm a reservation",
          "input": {
            "bookingId": {"type": "string", "required": true},
            "nights": {"type": "integer"},
            "guests": {"type": "array", "items": {"type": "string"}},
            "meta": {"type": "object"}
          },
          "output": {"confirmationCode": {"type": "string", "required": true}},
          "errors": [{"code": "SOLD_OUT", "description": "no rooms"}, {"code": "ALREADY_CONFIRMED"}]
        }""";

    @Test
    void generates_the_input_record_in_the_group_package_with_mapped_types() {
        var input = generate(FULL).get("ConfirmBookingV1Input");

        assertThat(input.packageName()).isEqualTo("com.acme.tasks.booking");
        assertThat(input.source())
                .contains("public record ConfirmBookingV1Input(")
                .contains("java.lang.String bookingId")
                .contains("java.lang.Long nights")
                .contains("java.util.List<java.lang.String> guests")
                .contains("com.fasterxml.jackson.databind.JsonNode meta");
    }

    @Test
    void generates_the_handler_interface_typed_to_the_records_with_error_subclasses() {
        var task = generate(FULL).get("ConfirmBookingV1Task");

        assertThat(task.source())
                .contains("public interface ConfirmBookingV1Task extends "
                        + "io.mateu.workflow.worker.api.TaskHandler<ConfirmBookingV1Input, ConfirmBookingV1Output>")
                .contains("final class SoldOut extends io.mateu.workflow.worker.api.TaskFailure")
                .contains("public SoldOut() { super(\"SOLD_OUT\"); }")
                .contains("public SoldOut(String reason) { super(\"SOLD_OUT\", reason); }")
                .contains("final class AlreadyConfirmed extends io.mateu.workflow.worker.api.TaskFailure");
    }

    @Test
    void generates_a_group_autoconfiguration_with_a_registration_bean_gated_by_enabled() {
        var files = generate(FULL);
        var config = files.get("BookingTasksAutoConfiguration");

        assertThat(config.packageName()).isEqualTo("com.acme.tasks.booking");
        assertThat(config.source())
                .contains("@org.springframework.boot.autoconfigure.AutoConfiguration")
                .contains("name = \"eventconductor.tasks.confirm-booking.enabled\", matchIfMissing = true")
                .contains("public io.mateu.workflow.worker.api.TaskRegistration"
                        + "<ConfirmBookingV1Input, ConfirmBookingV1Output> confirmBookingV1Registration(")
                .contains("ConfirmBookingV1Task handler)")
                .contains("\"confirm-booking\", 1, \"booking-ops\", "
                        + "ConfirmBookingV1Input.class, ConfirmBookingV1Output.class, handler");
    }

    @Test
    void lists_each_group_configuration_in_the_autoconfiguration_imports() {
        var generated = new TaskSourceGenerator("com.acme.tasks").generate(List.of(contract(FULL)));
        assertThat(generated.autoConfigurationImports())
                .isEqualTo("com.acme.tasks.booking.BookingTasksAutoConfiguration\n");
    }

    @Test
    void a_task_with_no_input_or_output_binds_to_void() {
        var files = generate("""
            {"id": "ping", "version": 1, "group": "ops"}""");

        assertThat(files).doesNotContainKeys("PingV1Input", "PingV1Output");
        assertThat(files.get("PingV1Task").source())
                .contains("extends io.mateu.workflow.worker.api.TaskHandler<java.lang.Void, java.lang.Void>");
        assertThat(files.get("OpsTasksAutoConfiguration").source())
                .contains("java.lang.Void.class, java.lang.Void.class, handler")
                .contains(", 1, null, ");
    }

    @Test
    void an_attribute_name_that_is_not_a_java_identifier_keeps_its_wire_name_via_jsonproperty() {
        var input = generate("""
            {"id": "x", "version": 1, "group": "g",
             "input": {"order-id": {"type": "string"}}}""").get("XV1Input");

        assertThat(input.source())
                .contains("@com.fasterxml.jackson.annotation.JsonProperty(\"order-id\") "
                        + "java.lang.String orderId");
    }

    @Test
    void several_versions_of_one_task_coexist_as_distinct_types() {
        var files = generate(
                "{\"id\": \"greet\", \"version\": 1, \"group\": \"g\", \"output\": {\"m\": {\"type\": \"string\"}}}",
                "{\"id\": \"greet\", \"version\": 2, \"group\": \"g\", \"output\": {\"m\": {\"type\": \"string\"}, \"n\": {\"type\": \"integer\"}}}");

        assertThat(files).containsKeys("GreetV1Task", "GreetV2Task", "GreetV1Output", "GreetV2Output");
        assertThat(files.get("GTasksAutoConfiguration").source())
                .contains("greetV1Registration(")
                .contains("greetV2Registration(");
    }

    @Test
    void each_group_gets_its_own_configuration_and_import() {
        var generated = new TaskSourceGenerator("com.acme.tasks").generate(List.of(
                contract("{\"id\": \"a\", \"version\": 1, \"group\": \"alpha\"}"),
                contract("{\"id\": \"b\", \"version\": 1, \"group\": \"beta\"}")));

        assertThat(generated.autoConfigurationImports())
                .contains("com.acme.tasks.alpha.AlphaTasksAutoConfiguration")
                .contains("com.acme.tasks.beta.BetaTasksAutoConfiguration");
    }

    @Test
    void generation_is_deterministic_same_contracts_same_bytes() {
        var a = new TaskSourceGenerator("com.acme.tasks").generate(List.of(contract(FULL)));
        var b = new TaskSourceGenerator("com.acme.tasks").generate(List.of(contract(FULL)));

        assertThat(b.autoConfigurationImports()).isEqualTo(a.autoConfigurationImports());
        assertThat(b.javaFiles()).isEqualTo(a.javaFiles());
    }

    @Test
    void generates_a_spring_boot_application_class_for_a_standalone_service() {
        var app = new TaskSourceGenerator("com.acme.svc")
                .applicationClass("com.acme.svc.TaskServiceApplication");

        assertThat(app.packageName()).isEqualTo("com.acme.svc");
        assertThat(app.typeName()).isEqualTo("TaskServiceApplication");
        assertThat(app.source())
                .contains("package com.acme.svc;")
                .contains("@org.springframework.boot.autoconfigure.SpringBootApplication")
                .contains("public class TaskServiceApplication")
                .contains("org.springframework.boot.SpringApplication.run(TaskServiceApplication.class, args)");
    }

    @Test
    void maps_the_scalar_types() {
        var input = generate("""
            {"id": "t", "version": 1, "group": "g", "input": {
              "s": {"type": "string"}, "i": {"type": "integer"}, "n": {"type": "number"},
              "b": {"type": "boolean"}, "d": {"type": "date"}, "dt": {"type": "datetime"}}}""")
                .get("TV1Input");

        assertThat(input.source())
                .contains("java.lang.String s")
                .contains("java.lang.Long i")
                .contains("java.math.BigDecimal n")
                .contains("java.lang.Boolean b")
                .contains("java.time.LocalDate d")
                .contains("java.time.LocalDateTime dt");
    }
}
