package com.fnph.telepsychiatric.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;

/**
 * Timestamps leave the API with an explicit UTC offset, and come in with or
 * without one.
 *
 * The JVM is pinned to UTC in the application main, so a LocalDateTime is UTC
 * wall-clock and can be labelled "Z". Conversion to WAT is the client's job.
 *
 * Registered last, twice over. A LocalDateTime serializer that wrote nothing
 * broke every response containing a date ("Can not write a field name,
 * expecting a value"); Spring then forwarded the failure to /error, which
 * security answered with 401, so the browser saw a 401 instead of the real
 * fault. Registering this module after everything else means no other
 * serializer for LocalDateTime can win:
 *  - postConfigurer runs after every other builder setting and customizer
 *  - extendMessageConverters covers the MVC JSON converters even if a custom
 *    ObjectMapper bean bypassed the builder
 * Jackson's own LocalDateTimeSerializer does the writing.
 */
@Configuration
public class JacksonTimeConfig implements WebMvcConfigurer {

    /** Microsecond precision, matching DATETIME(6), with a literal Z. */
    static final DateTimeFormatter UTC_WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTime() {
        return builder -> builder.postConfigurer(mapper -> mapper.registerModule(utcModule()));
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                ObjectMapper mapper = jackson.getObjectMapper();
                mapper.registerModule(utcModule());
            }
        }
    }

    /**
     * Unnamed on purpose: a SimpleModule without an explicit name is never
     * treated as a duplicate, so this registration always lands, and lands last.
     */
    static SimpleModule utcModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(UTC_WIRE));
        module.addDeserializer(LocalDateTime.class, new UtcLocalDateTimeDeserializer());
        return module;
    }

    /** Accepts an offset (converted to UTC) or a bare value (taken as UTC). */
    static final class UtcLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

        UtcLocalDateTimeDeserializer() {
            super(LocalDateTime.class);
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME
                    .parseBest(text.trim(), OffsetDateTime::from, LocalDateTime::from);
            if (parsed instanceof OffsetDateTime odt) {
                return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return (LocalDateTime) parsed;
        }
    }
}