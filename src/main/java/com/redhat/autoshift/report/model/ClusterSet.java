package com.redhat.autoshift.report.model;

import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record ClusterSet(String name, String environment, String type, Map<String, Object> values, Map<String, String> labels,
                         Path source) {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterSet.class);

    public String sourceName() {

        return source == null || source.getFileName() == null ? "unknown" : source.getFileName().toString();
    }

    public String displayName() {
        return environment + "/" + name;
    }

    public String id() {

        return sourceName() + ":" + name;
    }

    public String environment() {

        String env;
        if (source == null || source.getNameCount() < 2) {
            env = "root";
        } else {
            env = source.getName(0).toString();
        }
        LOG.debug("Name {} Env {}", name, env);

        return env;
    }

}
