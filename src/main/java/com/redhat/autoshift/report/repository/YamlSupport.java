package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

@Component
public class YamlSupport {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {

        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {

        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String string(Object value) {

        return value == null ? null : String.valueOf(value);
    }

    public static boolean trueValue(Object value) {

        return "true".equalsIgnoreCase(string(value));
    }

    public static boolean falseValue(Object value) {

        return "false".equalsIgnoreCase(string(value));
    }

    public Map<String, Object> read(Path file) throws IOException {

        Map<String, Object> value = mapper.readValue(file.toFile(), new TypeReference<>() {

        });
        return value == null ? Map.of() : value;
    }

}
