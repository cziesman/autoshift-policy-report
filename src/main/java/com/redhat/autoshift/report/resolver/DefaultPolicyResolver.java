package com.redhat.autoshift.report.resolver;

import java.util.List;
import java.util.Objects;

import com.redhat.autoshift.report.model.Cluster;
import com.redhat.autoshift.report.model.ClusterPolicyState;
import com.redhat.autoshift.report.model.ClusterReport;
import com.redhat.autoshift.report.model.ClusterSet;
import com.redhat.autoshift.report.model.PolicyDefinition;
import com.redhat.autoshift.report.model.PolicyEvaluation;
import com.redhat.autoshift.report.model.PolicyRule;
import com.redhat.autoshift.report.model.PolicyState;
import com.redhat.autoshift.report.model.PolicySummary;
import org.springframework.stereotype.Component;

@Component
public class DefaultPolicyResolver implements PolicyResolver {

    @Override
    public ClusterReport clusterReport(Cluster cluster, ClusterSet clusterSet, List<PolicyDefinition> policies, List<Cluster> allClusters) {

        return new ClusterReport(cluster, clusterSet, policies.stream().map(p -> evaluate(cluster, clusterSet, p)).toList());
    }

    @Override
    public List<PolicyEvaluation> clusterSetPolicies(ClusterSet clusterSet, List<PolicyDefinition> policies) {

        return policies.stream().map(p -> evaluateClusterSet(clusterSet, p)).toList();
    }

    private PolicyEvaluation evaluateClusterSet(ClusterSet clusterSet, PolicyDefinition policy) {

        if (policy.excluded()) {
            return new PolicyEvaluation(policy, PolicyState.EXCLUDED, "global excludePolicies", null);
        }
        if (policy.rules().isEmpty()) {
            return new PolicyEvaluation(policy, PolicyState.UNKNOWN, "no placement rule discovered", null);
        }
        for (PolicyRule rule : policy.rules()) {
            if (!rule.clusterSets().isEmpty() && !rule.clusterSets().contains(clusterSet.name())) {
                continue;
            }
            String value = clusterSet.labels().get(rule.labelKey());
            if (value != null) {
                return result(policy, value, "ClusterSet: " + clusterSet.name(), rule.labelKey());
            }
        }
        return new PolicyEvaluation(policy, PolicyState.NOT_APPLICABLE, "not configured", null);
    }

    @Override
    public List<PolicySummary> policySummaries(List<Cluster> clusters, List<ClusterSet> clusterSets, List<PolicyDefinition> policies) {

        return policies.stream().map(policy -> {
            List<ClusterPolicyState> clusterSetStates = clusterSets.stream()
                    .map(clusterSet -> {
                        PolicyEvaluation evaluation = evaluateClusterSet(clusterSet, policy);
                        return new ClusterPolicyState(clusterSet.id(), clusterSet.name(),
                                evaluation.state(), evaluation.source());
                    }).toList();

            List<ClusterPolicyState> states = clusters.stream().map(cluster -> {
                ClusterSet set = resolveClusterSet(cluster, clusterSets);
                PolicyEvaluation evaluation = evaluate(cluster, set, policy);
                return new ClusterPolicyState(cluster.id(), cluster.name(), evaluation.state(), evaluation.source());
            }).toList();
            long enabled = states.stream().filter(s -> s.state() == PolicyState.ENABLED).count();
            return new PolicySummary(policy, enabled, clusters.size(), states, clusterSetStates);
        }).toList();
    }

    private ClusterSet resolveClusterSet(Cluster cluster, List<ClusterSet> sets) {

        List<ClusterSet> matches = sets.stream()
                .filter(s -> "managedClusterSets".equals(s.type()))
                .filter(s -> Objects.equals(s.name(), cluster.clusterSet())).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            String base = stripExtension(cluster.sourceName());
            List<ClusterSet> sameProfile = matches.stream().filter(s -> stripExtension(s.sourceName()).equals(base)).toList();
            return sameProfile.size() == 1 ? sameProfile.get(0) : null;
        }
        matches = sets.stream().filter(s -> Objects.equals(s.name(), cluster.clusterSet())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String stripExtension(String value) {

        int i = value.lastIndexOf('.');
        return i > 0 ? value.substring(0, i) : value;
    }

    private PolicyEvaluation evaluate(Cluster cluster, ClusterSet set, PolicyDefinition policy) {

        if (policy.excluded()) {
            return new PolicyEvaluation(policy, PolicyState.EXCLUDED, "global excludePolicies", null);
        }
        if (policy.rules().isEmpty()) {
            return new PolicyEvaluation(policy, PolicyState.UNKNOWN, "no placement rule discovered", null);
        }
        for (PolicyRule rule : policy.rules()) {
            if (!rule.clusterSets().isEmpty() && (set == null || !rule.clusterSets().contains(set.name()))) {
                continue;
            }
            String clusterValue = cluster.labels().get(rule.labelKey());
            String setValue = set == null ? null : set.labels().get(rule.labelKey());
            if (clusterValue != null) {
                return result(policy, clusterValue, "Cluster: " + cluster.sourceName(), rule.labelKey());
            }
            if (setValue != null) {
                return result(policy, setValue, "ClusterSet: " + set.name(), rule.labelKey());
            }
        }
        return new PolicyEvaluation(policy, PolicyState.NOT_APPLICABLE, set == null ? "ambiguous ClusterSet" : "not configured", null);
    }

    private PolicyEvaluation result(PolicyDefinition policy, String value, String source, String key) {

        PolicyState state;
        if ("true".equalsIgnoreCase(value)) {
            state = PolicyState.ENABLED;
        } else if ("false".equalsIgnoreCase(value)) {
            state = PolicyState.DISABLED;
        } else {
            state = PolicyState.UNKNOWN;
        }
        return new PolicyEvaluation(policy, state, source, value);
    }

}
