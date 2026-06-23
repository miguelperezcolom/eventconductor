package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TimeoutDeserializer deserializer = new TimeoutDeserializer();

    private JsonParser parserFor(String json) throws Exception {
        JsonFactory factory = mapper.getFactory();
        JsonParser p = factory.createParser(json);
        p.nextToken();
        return p;
    }

    private DeserializationContext ctx() {
        return mapper.getDeserializationContext();
    }

    @Test
    void deserializesIntegerAsMilliseconds() throws Exception {
        JsonParser p = parserFor("30000");
        assertThat(deserializer.deserialize(p, ctx())).isEqualTo(30000L);
    }

    @Test
    void deserializesIso8601DurationString() throws Exception {
        JsonParser p = parserFor("\"PT30S\"");
        assertThat(deserializer.deserialize(p, ctx())).isEqualTo(30_000L);
    }

    @Test
    void deserializesIso8601MinutesDuration() throws Exception {
        JsonParser p = parserFor("\"PT5M\"");
        assertThat(deserializer.deserialize(p, ctx())).isEqualTo(300_000L);
    }

    @Test
    void deserializesNullAsZero() throws Exception {
        JsonParser p = parserFor("null");
        assertThat(deserializer.deserialize(p, ctx())).isEqualTo(0L);
    }

    @Test
    void getNullValueReturnsZero() {
        assertThat(deserializer.getNullValue(ctx())).isEqualTo(0L);
    }

    @Test
    void deserializesEmptyStringAsZero() throws Exception {
        JsonParser p = parserFor("\"\"");
        assertThat(deserializer.deserialize(p, ctx())).isEqualTo(0L);
    }
}
