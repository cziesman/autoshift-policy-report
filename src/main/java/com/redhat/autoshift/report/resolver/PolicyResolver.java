package com.redhat.autoshift.report.resolver;

import java.util.List;

import com.redhat.autoshift.report.model.Cluster;
import com.redhat.autoshift.report.model.ClusterReport;
import com.redhat.autoshift.report.model.ClusterSet;
import com.redhat.autoshift.report.model.PolicyDefinition;
import com.redhat.autoshift.report.model.PolicyEvaluation;
import com.redhat.autoshift.report.model.PolicySummary;

public interface PolicyResolver {

    ClusterReport clusterReport(Cluster cluster, ClusterSet clusterSet, List<PolicyDefinition> policies, List<Cluster> allClusters);

    List<PolicyEvaluation> clusterSetPolicies(ClusterSet clusterSet, List<PolicyDefinition> policies);

    List<PolicySummary> policySummaries(List<Cluster> clusters, List<ClusterSet> clusterSets, List<PolicyDefinition> policies);

}
