package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Reads every YAML document in a file for policy rule discovery.
     *
     * Policy templates are intentionally read from their original source elsewhere
     * (for example, by the policy YAML viewer). For rule discovery only, Helm
     * template expressions are replaced with harmless YAML scalars and Helm control
     * directives are removed. This makes templated YAML parseable without changing
     * the repository content that is displayed to the user.
     */
    public List<Object> readDocuments(Path file) throws IOException {
        String source = Files.readString(file);
        String parseable = makeHelmTemplateTolerant(source);

        List<Object> documents = new ArrayList<>();
        try (var parser = mapper.getFactory().createParser(parseable)) {
            var iterator = mapper.readerFor(Object.class).readValues(parser);
            while (iterator.hasNextValue()) {
                Object value = iterator.nextValue();
                if (value != null) {
                    documents.add(value);
                }
            }
        }
        return documents;
    }

    private String makeHelmTemplateTolerant(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int blockScalarIndent = -1;

        for (String line : source.split("\\R", -1)) {
            int indent = indentation(line);
            String trimmed = line.trim();

            // Once YAML enters a block scalar, all of its contents are literal
            // scalar text. Helm expressions inside the scalar must not be
            // interpreted as YAML or removed.
            if (blockScalarIndent >= 0) {
                if (trimmed.isEmpty() || indent > blockScalarIndent) {
                    result.append(line).append('\n');
                    continue;
                }
                blockScalarIndent = -1;
            }

            if (isBlockScalarHeader(trimmed)) {
                blockScalarIndent = indent;
                result.append(line).append('\n');
                continue;
            }

            // A line containing only a Helm expression/directive is not YAML.
            // This includes assignments such as "{{- $policyName := ... }}"
            // as well as if/range/end statements. Removing the line avoids
            // leaving an indented scalar before the next YAML mapping key.
            if (isHelmOnlyLine(trimmed)) {
                result.append('\n');
                continue;
            }

            // Replace Helm expressions only in ordinary YAML content. The
            // original source remains unchanged for the policy viewer.
            result.append(replaceHelmExpressions(line)).append('\n');
        }

        return result.toString();
    }

    private static int indentation(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean isBlockScalarHeader(String trimmed) {
        return trimmed.matches(".*:\\s*[|>][0-9+-]*\\s*(?:#.*)?$");
    }

    private static boolean isHelmOnlyLine(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }

        int position = 0;
        while (position < trimmed.length()) {
            int start = trimmed.indexOf("{{", position);
            if (start < 0 || !trimmed.substring(0, start).trim().isEmpty()) {
                return false;
            }

            int end = trimmed.indexOf("}}", start + 2);
            if (end < 0) {
                return false;
            }

            position = end + 2;
            if (!trimmed.substring(position).trim().isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String replaceHelmExpressions(String line) {
        StringBuilder result = new StringBuilder(line.length());
        int position = 0;

        while (position < line.length()) {
            int start = line.indexOf("{{", position);
            if (start < 0) {
                result.append(line, position, line.length());
                break;
            }

            result.append(line, position, start);
            int end = line.indexOf("}}", start + 2);
            if (end < 0) {
                result.append(line, start, line.length());
                break;
            }

            result.append("TEMPLATE_VALUE");
            position = end + 2;
        }

        return result.toString();
    }
}
