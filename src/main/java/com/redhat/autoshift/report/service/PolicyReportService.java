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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PolicyReportService {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyReportService.class);

    @Autowired
    private AutoShiftRepository repository;

    @Autowired
    private PolicyResolver resolver;

    @Autowired
    private AutoShiftProperties properties;

    @Autowired
    private com.redhat.autoshift.report.repository.RepositorySourceFactory repositorySourceFactory;

    private volatile CachedReport cachedReport;

    /**
     * A single refresh worker prevents concurrent users from triggering duplicate
     * repository refreshes while allowing requests to continue using the last
     * successfully built report.
     */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "autoshift-report-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean refreshInProgress = new AtomicBoolean();

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

        LOG.debug("Checking report cache");

        CachedReport current = cachedReport;
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(0L, properties.getCacheSeconds()) * 1000L;

        if (current != null && cacheMillis > 0 && now - current.createdAt() < cacheMillis) {
            LOG.debug("Returning cached report");
            return current.report();
        }

        if (cacheMillis <= 0) {
            synchronized (this) {
                LOG.info("Report caching disabled; rebuilding report");
                return rebuildReport();
            }
        }

        // Once a report exists, don't make every user's request wait for Git/network
        // access. Start one background refresh and continue serving the last good report.
        if (current != null) {
            scheduleRefresh();
            LOG.debug("Returning stale report while refresh is in progress");
            return current.report();
        }

        // There is no report yet, so the first request must build it synchronously.
        synchronized (this) {
            current = cachedReport;
            if (current != null) {
                return current.report();
            }
            LOG.info("Report cache empty; building initial report");
            return rebuildReport();
        }
    }

    private void scheduleRefresh() {

        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }

        refreshExecutor.submit(() -> {
            try {
                synchronized (this) {
                    LOG.info("Refreshing report cache in background");
                    rebuildReport();
                }
            } catch (Exception e) {
                // Keep the previous successful report available. A transient Git
                // or parsing failure must not make the application unavailable.
                LOG.error("Unable to refresh report cache; retaining previous report", e);
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private Report rebuildReport() throws IOException {

        long start = System.currentTimeMillis();
        List<Cluster> clusters = repository.clusters();
        List<ClusterSet> sets = repository.clusterSets();
        List<PolicyDefinition> policies = repository.policies();
        List<ClusterReport> clusterReports = clusters.stream().map(c ->
                resolver.clusterReport(c, resolveClusterSet(c, sets), policies, clusters)).toList();
        Report report = new Report(clusters, sets, policies, clusterReports,
                resolver.policySummaries(clusters, sets, policies));
        LOG.info(
                "Report rebuilt in {} ms: {} policies, {} clustersets, {} clusters",
                System.currentTimeMillis() - start,
                report.policies().size(),
                report.clusterSets().size(),
                report.clusters().size());
        cachedReport = new CachedReport(report, System.currentTimeMillis());
        return report;
    }

    public void clearCache() {

        cachedReport = null;
    }

    @PreDestroy
    public void shutdown() {

        refreshExecutor.shutdownNow();
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

        List<Cluster> members = report.clusters().stream()
                .filter(c -> name.equals(c.clusterSet()))
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
