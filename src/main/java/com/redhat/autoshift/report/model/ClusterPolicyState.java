package com.redhat.autoshift.report.model;

public record ClusterPolicyState(String clusterId, String cluster, PolicyState state, String source) {

    public String sourceName() {

        int i = clusterId == null ? -1 : clusterId.indexOf(':');
        return i > 0 ? clusterId.substring(0, i) : clusterId;
    }

}
