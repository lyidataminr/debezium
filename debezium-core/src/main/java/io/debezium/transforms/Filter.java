/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.transforms;

import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.common.annotation.Incubating;
import io.debezium.config.Configuration;
import io.debezium.config.EnumeratedValue;
import io.debezium.config.Field;
import io.debezium.transforms.filter.Engine;
import io.debezium.transforms.filter.GraalJsEngine;
import io.debezium.transforms.filter.Jsr223Engine;

/**
 * This SMT should allow user to filter out records depending on an expression and language configured.
 * Current implementation supports only Groovy scripting language.<p/>
 * The SMT will instantiate an scripting engine encapsulated in {@code Engine} interface in configure phase.
 * It will try to pre-parse the expression if it is allowed by the engine and than the expression is evaluated
 * for every record incoming.<p>
 * The engine will extract key, value and its schemas and will inject them as variables into the engine.
 * The mapping is unique for each expression language.
 *
 * @param <R> the subtype of {@link ConnectRecord} on which this transformation will operate
 * @author Jiri Pechanec
 */
@Incubating
public class Filter<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Filter.class);

    private static final String JAVAX_SCRIPT_ENGINE_PREFIX = "jsr223.";
    private static final String GRAAL_JS_ENGINE = "graal.js";

    public static enum NullHandling implements EnumeratedValue {
        DROP("drop"),
        KEEP("keep"),
        EVALUATE("evaluate");

        private final String value;

        private NullHandling(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static NullHandling parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (NullHandling option : NullHandling.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static NullHandling parse(String value, String defaultValue) {
            NullHandling mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }
    }

    public static final Field LANGUAGE = Field.create("language")
            .withDisplayName("Expression language")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.HIGH)
            .withValidation(Field::isRequired)
            .withDescription("An expression language used to evaluate the filtering condition. 'groovy' and 'graal.js' are supported.");

    public static final Field EXPRESSION = Field.create("condition")
            .withDisplayName("Filtering condition")
            .withType(ConfigDef.Type.STRING)
            .withWidth(ConfigDef.Width.MEDIUM)
            .withImportance(ConfigDef.Importance.HIGH)
            .withValidation(Field::isRequired)
            .withDescription("An expression determining whether the record should be filtered out. When evaluated to true the record is removed.");

    public static final Field NULL_HANDLING = Field.create("null.handling.mode")
            .withDisplayName("Handle null records")
            .withEnum(NullHandling.class, NullHandling.KEEP)
            .withWidth(ConfigDef.Width.SHORT)
            .withImportance(ConfigDef.Importance.LOW)
            .withDescription("How to handle records with null value. Options are: "
                    + "keep - records are passed (the default),"
                    + "drop - records are removed,"
                    + "evaluate - the null records are passed for evaluation.");

    private Engine engine;
    private NullHandling nullHandling;

    @Override
    public void configure(Map<String, ?> configs) {
        final Configuration config = Configuration.from(configs);

        final Field.Set configFields = Field.setOf(LANGUAGE, EXPRESSION, NULL_HANDLING);
        if (!config.validateAndRecord(configFields, LOGGER::error)) {
            throw new DebeziumException("The provided configuration isn't valid; check the error log for details.");
        }

        final String expression = config.getString(EXPRESSION);
        String language = config.getString(LANGUAGE);

        LOGGER.info("Using language '{}' to evaluate expression '{}'", language, expression);

        nullHandling = NullHandling.parse(config.getString(NULL_HANDLING));

        // currently only bootstrapping via JSR 223 is supported, but we could add
        // support for other means of bootstrapping later on, e.g. for "native"
        // bootstrap of GraalJS
        if (!language.startsWith(JAVAX_SCRIPT_ENGINE_PREFIX)) {
            throw new DebeziumException("Value for option '" + LANGUAGE + "' must begin with 'jsr223.', e.g. 'jsr223.groovy'");
        }
        else {
            language = language.substring(JAVAX_SCRIPT_ENGINE_PREFIX.length());
        }

        // graal.js needs a bit of extra-config...
        if (language.equals(GRAAL_JS_ENGINE)) {
            engine = new GraalJsEngine();
        }
        else {
            engine = new Jsr223Engine();
        }

        try {
            engine.configure(language, expression);
        }
        catch (Exception e) {
            throw new DebeziumException("Failed to parse filtering expression '" + expression + "'", e);
        }
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            if (nullHandling == NullHandling.KEEP) {
                return record;
            }
            else if (nullHandling == NullHandling.DROP) {
                return null;
            }
        }
        return engine.eval(record) ? record : null;
    }

    @Override
    public ConfigDef config() {
        final ConfigDef config = new ConfigDef();
        Field.group(config, null, LANGUAGE, EXPRESSION, NULL_HANDLING);
        return config;
    }

    @Override
    public void close() {
    }
}
