package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hook that turns a dropped record into a reported one.
 *
 * <p>Worth testing away from Kafka because the interesting part is not the parking — it is which
 * record gets parked. Spring Cloud Stream reports a batch failure with an index that counts the
 * records that <em>converted</em>, not the position of the one that did not, and the header lists it
 * refers to are being trimmed by another helper as it goes. Reading the offending payload out of
 * that arithmetic is exactly the kind of thing that looks right and parks the wrong record.
 */
class UnreadableRecordReporterTest {

    static final String READABLE = """
            {"type":"process-creation-requested","workflowDefinitionId":"checkin",\
            "businessKey":"good","variables":[]}""";

    /** Balanced braces, and an escape no parser accepts. */
    static final String UNREADABLE = """
            {"type":"process-creation-requested","workflowDefinitionId":"checkin",\
            "businessKey":"bad","variables":[{"name":"TEST_CONFIG","value":"{\\}"}]}""";

    record Parked(byte[] payload, Throwable cause, String source) {
        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    final List<Parked> parked = new ArrayList<>();

    UnreadableRecordReporter reporter;

    @BeforeEach
    void setUp() {
        parked.clear();
        reporter = new UnreadableRecordReporter(new DeadLetterPublisher() {
            @Override
            public void park(DomainEvent event, Throwable cause, String source) {
                throw new AssertionError("an unreadable record has no event to park");
            }

            @Override
            public void parkUnreadable(byte[] payload, Throwable cause, String source) {
                parked.add(new Parked(payload, cause, source));
            }
        });
    }

    @Test
    void the_unreadable_record_of_a_batch_is_parked_and_the_readable_ones_are_not() {
        reporter.postProcessBatchMessageOnFailure(batch(READABLE, UNREADABLE, READABLE), 2);

        assertThat(parked).hasSize(1);
        assertThat(parked.getFirst().text()).isEqualTo(UNREADABLE);
        assertThat(parked.getFirst().source()).isEqualTo("upstream");
    }

    @Test
    void the_cause_says_what_was_wrong_with_it_rather_than_that_something_was() {
        reporter.postProcessBatchMessageOnFailure(batch(UNREADABLE), 0);

        // The batch hook is handed no cause at all — it is derived here by re-reading the bytes,
        // because "could not be converted" without saying why is most of the way back to silence.
        assertThat(parked.getFirst().cause())
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void a_batch_is_reported_once_however_many_of_its_records_fail() {
        var batch = batch(UNREADABLE, UNREADABLE, READABLE);

        // Spring Cloud Stream calls the hook once per failing record; the batch is read once.
        reporter.postProcessBatchMessageOnFailure(batch, 0);
        reporter.postProcessBatchMessageOnFailure(batch, 0);

        assertThat(parked).hasSize(2).allSatisfy(record ->
                assertThat(record.text()).isEqualTo(UNREADABLE));
    }

    @Test
    void a_single_record_binding_parks_the_record_with_the_cause_it_was_given() {
        var cause = new IllegalStateException("no converter could read it");

        var fail = reporter.shouldFailIfCantConvert(
                MessageBuilder.withPayload(UNREADABLE.getBytes(StandardCharsets.UTF_8))
                        .setHeader("kafka_receivedTopic", "worker-reply").build(), cause);

        assertThat(parked).hasSize(1);
        assertThat(parked.getFirst().cause()).isSameAs(cause);
        assertThat(parked.getFirst().source()).isEqualTo("worker-reply");
        // Never redeliver: bytes that cannot be read once cannot be read the tenth time either, and
        // a batch that keeps failing keeps its partition for itself.
        assertThat(fail).isFalse();
    }

    @Test
    void a_batch_whose_records_all_parse_parks_nothing() {
        // The converter failed for a reason re-reading cannot reproduce. Nothing is parked, because
        // there is nothing to point at — the log line is the whole report in that case.
        reporter.postProcessBatchMessageOnFailure(batch(READABLE, READABLE), 1);

        assertThat(parked).isEmpty();
    }

    private static org.springframework.messaging.Message<List<byte[]>> batch(String... records) {
        var payloads = new ArrayList<byte[]>();
        var topics = new ArrayList<String>();
        for (var record : records) {
            payloads.add(record.getBytes(StandardCharsets.UTF_8));
            topics.add("upstream");
        }
        return MessageBuilder.withPayload((List<byte[]>) payloads)
                .setHeader("kafka_receivedTopic", topics)
                .build();
    }
}
