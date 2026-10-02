package com.redhat.autoshift.report.model;

import java.nio.file.Path;
import java.util.Map;

public record ClusterSet(String name, String type, Map<String, Object> values, Map<String, String> labels,
                         Path source) {

    /**
     * A ClusterSet name is only unique within its values file and namespace.
     */
    public String id() {

        return sourceName() + ":" + type + "/" + name;
    }

    public String sourceName() {

        return source == null ? "unknown" : source.toString().replace('\\', '/');
    }

    public String environment() {

        if (source == null || source.getNameCount() < 2) {
            return "root";
        }
        return source.getName(0).toString();
    }

}
