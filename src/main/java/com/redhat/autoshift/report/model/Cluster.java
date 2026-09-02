package com.redhat.autoshift.report.model;

import java.nio.file.Path;
import java.util.Map;

public record Cluster(String name, String clusterSet, Map<String, Object> values, Map<String, String> labels,
                      Path source) {

    public String sourceName() {

        return source == null || source.getFileName() == null ? "unknown" : source.getFileName().toString();
    }

    /**
     * A cluster name is only unique within its values file.
     */
    public String id() {

        return sourceName() + ":" + name;
    }

}
