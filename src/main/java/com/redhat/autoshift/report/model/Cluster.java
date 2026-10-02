package com.redhat.autoshift.report.model;

import java.nio.file.Path;
import java.util.Map;

public record Cluster(String name, String clusterSet, Map<String, Object> values, Map<String, String> labels,
                      Path source) {

    public String sourceName() {

        return source == null ? "unknown" : source.toString().replace('\\', '/');
    }

    /**
     * A cluster name is only unique within its values file.
     */
    public String environment() {

        if (source == null || source.getNameCount() < 2) {
            return "root";
        }
        return source.getName(0).toString();
    }

    public String id() {

        return sourceName() + ":" + name;
    }

}
