package com.redhat.autoshift.report.model;

import java.util.List;
import java.util.Map;

public record ClusterSetReport(
        ClusterSet clusterSet,
        List<Cluster> clusters,
        List<PolicyEvaluation> policies,
        Map<String, Object> config,
        String repositoryLocation) {

}
