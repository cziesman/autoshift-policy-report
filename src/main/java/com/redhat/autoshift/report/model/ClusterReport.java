package com.redhat.autoshift.report.model;

import java.util.List;

public record ClusterReport(Cluster cluster, ClusterSet clusterSet, List<PolicyEvaluation> policies) {

}
