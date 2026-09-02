package com.redhat.autoshift.report.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.redhat.autoshift.report.config.AutoShiftProperties;
import com.redhat.autoshift.report.model.Cluster;
import com.redhat.autoshift.report.model.ClusterReport;
import com.redhat.autoshift.report.model.ClusterSet;
import com.redhat.autoshift.report.model.ClusterSetReport;
import com.redhat.autoshift.report.model.PolicyDefinition;
import com.redhat.autoshift.report.model.PolicyEvaluation;
import com.redhat.autoshift.report.model.PolicySummary;
import com.redhat.autoshift.report.model.Report;
import com.redhat.autoshift.report.model.RepositoryInfo;
import com.redhat.autoshift.report.repository.AutoShiftRepository;
import com.redhat.autoshift.report.repository.YamlSupport;
import com.redhat.autoshift.report.resolver.PolicyResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PolicyReportService {

    @Autowired
    private AutoShiftRepository repository;

    @Autowired
    private PolicyResolver resolver;

    @Autowired
    private AutoShiftProperties properties;

    @Autowired
    private com.redhat.autoshift.report.repository.RepositorySourceFactory repositorySourceFactory;

    private volatile CachedReport cachedReport;

    public RepositoryInfo policiesRepositoryInfo() throws IOException {

        var config = properties.getPolicies();
        return new RepositoryInfo(config.getLocation(), config.getBranch(), "policies");
    }

    public RepositoryInfo siteValuesRepositoryInfo() throws IOException {

        var config = properties.getSiteValues();
        return new RepositoryInfo(config.getLocation(), config.getBranch(), "autoshift/values");
    }

    private com.redhat.autoshift.report.repository.RepositorySource repositorySourcePolicies() throws IOException {

        return repositorySourceFactory.policies();
    }

    private com.redhat.autoshift.report.repository.RepositorySource repositorySourceSiteValues() throws IOException {

        return repositorySourceFactory.siteValues();
    }

    public Report report() throws IOException {

        CachedReport current = cachedReport;
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(0L, properties.getCacheSeconds()) * 1000L;

        if (current != null && cacheMillis > 0 && now - current.createdAt() < cacheMillis) {
            return current.report();
        }

        synchronized (this) {
            current = cachedReport;
            now = System.currentTimeMillis();
            if (current != null && cacheMillis > 0 && now - current.createdAt() < cacheMillis) {
                return current.report();
            }

            // Repository access, including Git refreshes, happens only while rebuilding
            // the cached report rather than once for every page request.
            List<Cluster> clusters = repository.clusters();
            List<ClusterSet> sets = repository.clusterSets();
            List<PolicyDefinition> policies = repository.policies();
            List<ClusterReport> clusterReports = clusters.stream().map(c ->
                    resolver.clusterReport(c, resolveClusterSet(c, sets), policies, clusters)).toList();
            Report report = new Report(clusters, sets, policies, clusterReports,
                    resolver.policySummaries(clusters, sets, policies));
            cachedReport = new CachedReport(report, System.currentTimeMillis());
            return report;
        }
    }

    public void clearCache() {

        cachedReport = null;
    }

    public ClusterReport cluster(String sourceName, String name) throws IOException {

        return report().clusterReports().stream()
                .filter(r -> r.cluster().sourceName().equals(sourceName) && r.cluster().name().equals(name))
                .findFirst().orElse(null);
    }

    public ClusterReport cluster(String name) throws IOException {

        List<ClusterReport> matches = report().clusterReports().stream()
                .filter(r -> r.cluster().name().equals(name)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public PolicySummary policy(String name) throws IOException {

        return report().policySummaries().stream().filter(p -> p.policy().name().equals(name)).findFirst().orElse(null);
    }

    public ClusterSetReport clusterSet(String sourceName, String type, String name) throws IOException {

        Report report = report();
        ClusterSet clusterSet = report.clusterSets().stream()
                .filter(s -> s.sourceName().equals(sourceName))
                .filter(s -> s.type().equals(type))
                .filter(s -> s.name().equals(name))
                .findFirst().orElse(null);
        if (clusterSet == null) {
            return null;
        }

        List<Cluster> candidates = report.clusters().stream()
                .filter(c -> name.equals(c.clusterSet()))
                .toList();
        List<Cluster> members = candidates.stream()
                .filter(c -> sourceName.equals(c.sourceName()) || candidates.size() == 1)
                .toList();
        List<PolicyEvaluation> evaluations = resolver.clusterSetPolicies(clusterSet, report.policies());
        Map<String, Object> config = YamlSupport.map(clusterSet.values().get("config"));
        return new ClusterSetReport(clusterSet, members, evaluations, config,
                properties.getSiteValues().getLocation());
    }

    private ClusterSet resolveClusterSet(Cluster cluster, List<ClusterSet> sets) {

        List<ClusterSet> matches = sets.stream()
                .filter(s -> "managedClusterSets".equals(s.type()))
                .filter(s -> Objects.equals(s.name(), cluster.clusterSet()))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            // If the cluster and ClusterSet values files share a basename, use that profile.
            String clusterBase = stripExtension(cluster.sourceName());
            List<ClusterSet> sameProfile = matches.stream()
                    .filter(s -> stripExtension(s.sourceName()).equals(clusterBase)).toList();
            if (sameProfile.size() == 1) {
                return sameProfile.get(0);
            }
            return null; // ambiguous: never silently choose one
        }
        matches = sets.stream().filter(s -> Objects.equals(s.name(), cluster.clusterSet())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String stripExtension(String value) {

        int i = value.lastIndexOf('.');
        return i > 0 ? value.substring(0, i) : value;
    }

    private record CachedReport(Report report, long createdAt) {

    }

}
