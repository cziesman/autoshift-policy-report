package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.redhat.autoshift.report.model.Cluster;
import com.redhat.autoshift.report.model.ClusterSet;
import com.redhat.autoshift.report.model.PolicyDefinition;
import com.redhat.autoshift.report.model.PolicyRule;
import com.redhat.autoshift.report.model.PolicyTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class AutoShiftRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AutoShiftRepository.class);

    private final RepositorySourceFactory sources;

    private final YamlSupport yaml;

    public AutoShiftRepository(RepositorySourceFactory sources, YamlSupport yaml) {

        this.sources = sources;
        this.yaml = yaml;
    }

    public Path policiesRoot() throws IOException {
        return policiesRoot(null);
    }

    public Path policiesRoot(String branch) throws IOException {
        return resolvePoliciesRoot(sources.policies(branch).root());
    }

    public Path siteValuesRoot() throws IOException {
        return siteValuesRoot(null);
    }

    public Path siteValuesRoot(String branch) throws IOException {
        return resolveSiteValuesRoot(sources.siteValues(branch).root());
    }

    private Path resolvePoliciesRoot(Path root) throws IOException {
        // The configured location is normally the repository root.
        if (Files.isDirectory(root.resolve("policies"))) {
            return root.resolve("policies");
        }

        // Also support configuring the policies directory itself.
        if (Files.isDirectory(root.resolve("stable"))
                || Files.isDirectory(root.resolve("certified"))
                || Files.isDirectory(root.resolve("community"))) {
            return root;
        }

        throw new IOException("Policy repository does not contain policies/{stable,certified,community}: " + root);
    }

    private Path resolveSiteValuesRoot(Path root) throws IOException {

        Path[] candidates = {
                root.resolve("autoshift/values"),
                root.resolve("values"),
                root
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("clusters")) || Files.isDirectory(candidate.resolve("clustersets"))) {
                return candidate;
            }
        }
        throw new IOException("Site values repository does not contain clusters/ or clustersets/: " + root);
    }

    public String policiesSource() throws IOException {
        return sources.policies().displayName();
    }

    public String policiesSource(String branch) throws IOException {
        return sources.policies(branch).displayName();
    }

    public String siteValuesSource() throws IOException {
        return sources.siteValues().displayName();
    }

    public String siteValuesSource(String branch) throws IOException {
        return sources.siteValues(branch).displayName();
    }

    public Map<String, Object> global() throws IOException {
        return global(null);
    }

    public Map<String, Object> global(String branch) throws IOException {
        Path file = siteValuesRoot(branch).resolve("global.yaml");
        return Files.exists(file) ? yaml.read(file) : Map.of();
    }

    public List<ClusterSet> clusterSets() throws IOException {
        return clusterSets(null);
    }

    public List<ClusterSet> clusterSets(String branch) throws IOException {
        Path dir = siteValuesRoot(branch).resolve("clustersets");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ClusterSet> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(this::isYaml).filter(this::isRealValuesFile).sorted().toList()) {
                result.addAll(parseClusterSets(yaml.read(file), file));
            }
        }
        return result.stream().sorted(Comparator.comparing(ClusterSet::sourceName)
                .thenComparing(ClusterSet::type).thenComparing(ClusterSet::name)).toList();
    }

    public Optional<ClusterSet> findClusterSet(String sourceName, String type, String name) throws IOException {

        return clusterSets().stream().filter(cs -> cs.sourceName().equals(sourceName))
                .filter(cs -> cs.type().equals(type)).filter(cs -> cs.name().equals(name)).findFirst();
    }

    public List<Cluster> clusters() throws IOException {
        return clusters(null);
    }

    public List<Cluster> clusters(String branch) throws IOException {
        Path dir = siteValuesRoot(branch).resolve("clusters");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Cluster> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(this::isYaml).filter(this::isRealValuesFile).sorted().toList()) {
                Map<String, Object> clusters = YamlSupport.map(yaml.read(file).get("clusters"));
                for (var entry : clusters.entrySet()) {
                    Map<String, Object> c = YamlSupport.map(entry.getValue());
                    result.add(new Cluster(entry.getKey(),
                            YamlSupport.string(YamlSupport.map(c.get("config")).get("clusterSet")),
                            c, labels(c), file));
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(Cluster::name).thenComparing(Cluster::sourceName)).toList();
    }

    public Optional<Cluster> findCluster(String sourceName, String name) throws IOException {

        return clusters().stream().filter(c -> c.sourceName().equals(sourceName) && c.name().equals(name)).findFirst();
    }

    public List<PolicyDefinition> policies() throws IOException {
        return policies(null, null);
    }

    public List<PolicyDefinition> policies(String policyBranch) throws IOException {
        return policies(policyBranch, null);
    }

    /**
     * Loads policies from the policy repository branch while reading the
     * exclusion list from the independently selected site-values branch.
     */
    public List<PolicyDefinition> policies(String policyBranch, String siteValuesBranch) throws IOException {
        Path base = policiesRoot(policyBranch);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<PolicyDefinition> result = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        for (Object value : YamlSupport.list(global(siteValuesBranch).get("excludePolicies"))) {
            excluded.add(YamlSupport.string(value));
        }
        for (PolicyTier tier : PolicyTier.values()) {
            Path tierDir = base.resolve(tier.name().toLowerCase(Locale.ROOT));
            if (!Files.isDirectory(tierDir)) {
                continue;
            }
            try (var stream = Files.list(tierDir)) {
                for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                    result.add(new PolicyDefinition(dir.getFileName().toString(), tier, dir,
                            discoverRules(dir), excluded.contains(dir.getFileName().toString()),
                            readPolicyYaml(dir)));
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(PolicyDefinition::name)).toList();
    }

    private List<ClusterSet> parseClusterSets(Map<String, Object> doc, Path file) {

        List<ClusterSet> result = new ArrayList<>();
        for (String type : List.of("hubClusterSets", "managedClusterSets")) {
            Map<String, Object> sets = YamlSupport.map(doc.get(type));
            for (var entry : sets.entrySet()) {
                Map<String, Object> set = YamlSupport.map(entry.getValue());
                result.add(new ClusterSet(entry.getKey(), type, set, labels(set), file));
            }
        }
        return result;
    }

    private Map<String, String> labels(Map<String, Object> object) {

        Map<String, String> result = new TreeMap<>();
        YamlSupport.map(object.get("labels")).forEach((k, v) -> result.put(k, YamlSupport.string(v)));
        return result;
    }

    private String readPolicyYaml(Path policyDir) throws IOException {

        List<String> documents = new ArrayList<>();
        try (var walk = Files.walk(policyDir)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .sorted()
                    .toList()) {
                String content = Files.readString(file);
                String relative = policyDir.relativize(file).toString().replace('\\', '/');
                documents.add("# " + relative + "\n" + content.stripTrailing());
            }
        }
        return String.join("\n\n---\n\n", documents);
    }

    private List<PolicyRule> discoverRules(Path policyDir) throws IOException {

        List<PolicyRule> rules = new ArrayList<>();
        try (var walk = Files.walk(policyDir)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(this::isYaml).toList()) {
                try {
                    for (Object document : yaml.readDocuments(file)) {
                        scanForPlacementRules(document, rules);
                    }
                } catch (Exception e) {
                    LOG.error("Unable to parse policy YAML file {}", file, e);
                }
            }
        }
        return rules.stream().distinct().toList();
    }

    private void scanForPlacementRules(Object node, List<PolicyRule> rules) {

        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = YamlSupport.map(raw);
            if ("Placement".equals(map.get("kind")) && map.get("spec") instanceof Map<?, ?>) {
                Map<String, Object> specMap = YamlSupport.map(map.get("spec"));
                Set<String> clusterSets = new LinkedHashSet<>();
                for (Object cs : YamlSupport.list(specMap.get("clusterSets"))) {
                    clusterSets.add(YamlSupport.string(cs));
                }
                List<String> keys = new ArrayList<>();
                collectAutoShiftKeys(specMap, keys);
                for (String labelKey : keys) {
                    rules.add(new PolicyRule(labelKey, clusterSets));
                }
            }
            map.values().forEach(v -> scanForPlacementRules(v, rules));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> scanForPlacementRules(v, rules));
        }
    }

    private void collectAutoShiftKeys(Object node, List<String> keys) {

        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = YamlSupport.map(raw);
            Object key = map.get("key");
            if (key != null && YamlSupport.string(key).startsWith("autoshift.io/")) {
                keys.add(YamlSupport.string(key).substring("autoshift.io/".length()));
            }
            map.values().forEach(v -> collectAutoShiftKeys(v, keys));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectAutoShiftKeys(v, keys));
        }
    }

    private boolean isYaml(Path p) {

        String n = p.getFileName().toString();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    private boolean isRealValuesFile(Path p) {

        return !p.getFileName().toString().startsWith("_");
    }

}
