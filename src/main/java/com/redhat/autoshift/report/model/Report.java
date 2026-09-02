package com.redhat.autoshift.report.model;

import java.util.List;

public record Report(List<Cluster> clusters, List<ClusterSet> clusterSets, List<PolicyDefinition> policies,
                     List<ClusterReport> clusterReports, List<PolicySummary> policySummaries) {

    /**
     * Number of implemented policy cells shown in the summary matrix.
     */
    public long enabledCells() {

        return policySummaries.stream().mapToLong(PolicySummary::enabledCells).sum();
    }

}
