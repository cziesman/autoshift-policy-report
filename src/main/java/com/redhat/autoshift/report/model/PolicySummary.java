package com.redhat.autoshift.report.model;

import java.util.List;

public record PolicySummary(
        PolicyDefinition policy,
        long enabled,
        long total,
        List<ClusterPolicyState> clusters,
        List<ClusterPolicyState> clusterSets) {

    /**
     * Total number of implemented cells across ClusterSets and clusters.
     */
    public long enabledClusterSets() {

        return clusterSets.stream().filter(s -> s.state() == PolicyState.ENABLED).count();
    }

    public long enabledCells() {

        return enabled + enabledClusterSets();
    }

}
