package com.fnph.telepsychiatric.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

@Configuration
public class JacksonTimeConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTime() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new MyJsonSerializer())
                .deserializerByType(LocalDateTime.class, new JsonDeserializer<>() {
                    @Override public LocalDateTime deserialize(JsonParser parser,
                                                               DeserializationContext ctx) throws IOException {
                        String text = parser.getValueAsString();
                        if (text == null || text.isBlank()) return null;
                        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME
                                .parseBest(text.trim(), OffsetDateTime::from, LocalDateTime::from);
                        if (parsed instanceof OffsetDateTime odt) {
                            return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                        }
                        return (LocalDateTime) parsed;
                    }
                });
    }

    private static class MyJsonSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object o, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {

        }

        public void serialize(LocalDateTime value, JsonGenerator gen,
                              SerializerProvider p) throws IOException {
            gen.writeString(value.atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
