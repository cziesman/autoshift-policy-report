package com.redhat.autoshift.report.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.redhat.autoshift.report.repository.RepositorySourceFactory;
import com.redhat.autoshift.report.repository.YamlSupport;
import com.redhat.autoshift.report.resolver.PolicyResolver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PolicyReportService {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyReportService.class);
    private static final String DEFAULT_BRANCH = "main";

    @Autowired
    private AutoShiftRepository repository;

    @Autowired
    private PolicyResolver resolver;

    @Autowired
    private AutoShiftProperties properties;

    @Autowired
    private RepositorySourceFactory repositorySourceFactory;

    private final Map<String, CachedReport> cachedReports = new ConcurrentHashMap<>();
    private final Set<String> refreshesInProgress = ConcurrentHashMap.newKeySet();

    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "autoshift-report-refresh");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void initializeDefaultReportCache() {
        try {
            LOG.info("Initializing report cache at application startup for default policy branch {} and site-values branch {}",
                    DEFAULT_BRANCH, DEFAULT_BRANCH);
            report(DEFAULT_BRANCH, DEFAULT_BRANCH);
        } catch (Exception e) {
            LOG.error("Unable to initialize report cache at application startup for default policy branch {} and site-values branch {}",
                    DEFAULT_BRANCH, DEFAULT_BRANCH, e);
        }
    }

    public Set<String> availablePolicyBranches() throws IOException {
        return repositorySourceFactory.availablePolicyBranches();
    }

    public Set<String> availableSiteValuesBranches() throws IOException {
        return repositorySourceFactory.availableSiteValuesBranches();
    }

    public String defaultBranch() {
        return DEFAULT_BRANCH;
    }

    public RepositoryInfo policiesRepositoryInfo(String branch) {
        var config = properties.getPolicies();
        return new RepositoryInfo(config.getLocation(), normalizeBranch(branch), "policies");
    }

    public RepositoryInfo siteValuesRepositoryInfo(String branch) {
        var config = properties.getSiteValues();
        return new RepositoryInfo(config.getLocation(), normalizeBranch(branch), "autoshift/values");
    }

    public RepositoryInfo policiesRepositoryInfo() {
        return policiesRepositoryInfo(defaultBranch());
    }

    public RepositoryInfo siteValuesRepositoryInfo() {
        return siteValuesRepositoryInfo(defaultBranch());
    }

    public Report report() throws IOException {
        return report(defaultBranch(), defaultBranch());
    }

    public Report report(String policyBranch) throws IOException {
        return report(policyBranch, defaultBranch());
    }

    public Report report(String policyBranch, String siteValuesBranch) throws IOException {
        String selectedPolicyBranch = normalizeBranch(policyBranch);
        String selectedSiteValuesBranch = normalizeBranch(siteValuesBranch);
        String cacheKey = cacheKey(selectedPolicyBranch, selectedSiteValuesBranch);

        CachedReport current = cachedReports.get(cacheKey);
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(0L, properties.getCacheSeconds()) * 1000L;

        if (current != null && cacheMillis > 0 && now - current.createdAt() < cacheMillis) {
            return current.report();
        }

        if (cacheMillis <= 0) {
            synchronized (this) {
                LOG.info("Report caching disabled; rebuilding report for policy branch {} and site-values branch {}",
                        selectedPolicyBranch, selectedSiteValuesBranch);
                return rebuildReport(selectedPolicyBranch, selectedSiteValuesBranch, cacheKey);
            }
        }

        if (current != null) {
            scheduleRefresh(selectedPolicyBranch, selectedSiteValuesBranch, cacheKey);
            return current.report();
        }

        synchronized (this) {
            current = cachedReports.get(cacheKey);
            if (current != null) {
                return current.report();
            }
            LOG.info("Report cache empty; building initial report for policy branch {} and site-values branch {}",
                    selectedPolicyBranch, selectedSiteValuesBranch);
            return rebuildReport(selectedPolicyBranch, selectedSiteValuesBranch, cacheKey);
        }
    }

    private void scheduleRefresh(String policyBranch, String siteValuesBranch, String cacheKey) {
        if (!refreshesInProgress.add(cacheKey)) {
            return;
        }

        refreshExecutor.submit(() -> {
            try {
                synchronized (this) {
                    LOG.info("Refreshing report cache in background for policy branch {} and site-values branch {}",
                            policyBranch, siteValuesBranch);
                    rebuildReport(policyBranch, siteValuesBranch, cacheKey);
                }
            } catch (Exception e) {
                LOG.error("Unable to refresh report cache for policy branch {} and site-values branch {}; retaining previous report",
                        policyBranch, siteValuesBranch, e);
            } finally {
                refreshesInProgress.remove(cacheKey);
            }
        });
    }

    private Report rebuildReport(String policyBranch, String siteValuesBranch, String cacheKey) throws IOException {
        long start = System.currentTimeMillis();
        List<Cluster> clusters = repository.clusters(siteValuesBranch);
        List<ClusterSet> sets = repository.clusterSets(siteValuesBranch);
        List<PolicyDefinition> policies = repository.policies(policyBranch, siteValuesBranch);

        List<ClusterReport> clusterReports = clusters.stream()
                .map(c -> resolver.clusterReport(c, resolveClusterSet(c, sets), policies, clusters))
                .toList();

        Report report = new Report(
                clusters,
                sets,
                policies,
                clusterReports,
                resolver.policySummaries(clusters, sets, policies));

        LOG.info("Report rebuilt in {} ms for policy branch {} and site-values branch {}: {} policies, {} clustersets, {} clusters",
                System.currentTimeMillis() - start,
                policyBranch,
                siteValuesBranch,
                report.policies().size(),
                report.clusterSets().size(),
                report.clusters().size());

        cachedReports.put(cacheKey, new CachedReport(report, System.currentTimeMillis()));
        return report;
    }

    private String cacheKey(String policyBranch, String siteValuesBranch) {
        return policyBranch + "\\0" + siteValuesBranch;
    }

    public void clearCache() {
        cachedReports.clear();
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    public ClusterReport cluster(String sourceName, String name, String policyBranch, String siteValuesBranch) throws IOException {
        return report(policyBranch, siteValuesBranch).clusterReports().stream()
                .filter(r -> r.cluster().sourceName().equals(sourceName)
                        && r.cluster().name().equals(name))
                .findFirst().orElse(null);
    }

    public ClusterReport cluster(String name, String policyBranch, String siteValuesBranch) throws IOException {
        List<ClusterReport> matches = report(policyBranch, siteValuesBranch).clusterReports().stream()
                .filter(r -> r.cluster().name().equals(name)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public PolicySummary policy(String name, String policyBranch, String siteValuesBranch) throws IOException {
        return report(policyBranch, siteValuesBranch).policySummaries().stream()
                .filter(p -> p.policy().name().equals(name))
                .findFirst().orElse(null);
    }

    public ClusterSetReport clusterSet(String sourceName, String type, String name, String policyBranch, String siteValuesBranch) throws IOException {
        Report report = report(policyBranch, siteValuesBranch);
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

        return new ClusterSetReport(
                clusterSet,
                members,
                evaluations,
                config,
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
            String clusterBase = stripExtension(cluster.sourceName());
            List<ClusterSet> sameProfile = matches.stream()
                    .filter(s -> stripExtension(s.sourceName()).equals(clusterBase))
                    .toList();
            if (sameProfile.size() == 1) {
                return sameProfile.get(0);
            }
            return null;
        }

        matches = sets.stream()
                .filter(s -> Objects.equals(s.name(), cluster.clusterSet()))
                .toList();

        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String stripExtension(String value) {
        int i = value.lastIndexOf('.');
        return i > 0 ? value.substring(0, i) : value;
    }

    private String normalizeBranch(String branch) {
        return branch == null || branch.isBlank() ? DEFAULT_BRANCH : branch;
    }

    private record CachedReport(Report report, long createdAt) {
    }
}
