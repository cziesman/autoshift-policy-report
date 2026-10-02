package com.redhat.autoshift.report;

import com.redhat.autoshift.report.config.AutoShiftProperties;
import com.redhat.autoshift.report.repository.AutoShiftRepository;
import com.redhat.autoshift.report.repository.YamlSupport;
import com.redhat.autoshift.report.repository.RepositorySourceFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AutoShiftRepositoryTest {
    private AutoShiftProperties properties() {
        AutoShiftProperties p = new AutoShiftProperties();
        p.getPolicies().setLocation(Paths.get("src/test/resources/policy-repo-sample").toAbsolutePath().toString());
        p.getPolicies().setBranch("main");
        p.getPolicies().setToken("test-policy-token");
        p.getSiteValues().setLocation(Paths.get("src/test/resources/site-values-sample").toAbsolutePath().toString());
        p.getSiteValues().setBranch("main");
        return p;
    }

    @Test
    void readsHelmTemplatedAndMultiDocumentYaml() throws Exception {
        Path file = Files.createTempFile("policy-", ".yaml");
        Files.writeString(file, """
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: {{ .Values.policy_namespace }}
                ---
                apiVersion: cluster.open-clusters-management.io/v1beta1
                kind: Placement
                metadata:
                  name: placement
                spec:
                  predicates:
                    - requiredClusterSelector:
                        labelSelector:
                          matchExpressions:
                            - key: autoshift.io/environment
                              operator: In
                              values:
                                - production
                """);

        YamlSupport yaml = new YamlSupport();
        var documents = yaml.readDocuments(file);

        assertThat(documents).hasSize(2);
        assertThat(YamlSupport.map(documents.get(0)).get("kind")).isEqualTo("Namespace");
        assertThat(YamlSupport.map(documents.get(1)).get("kind")).isEqualTo("Placement");
    }

    @Test
    void repositoryPropertiesSupportAuthenticationTokens() {
        AutoShiftProperties properties = properties();

        assertThat(properties.getPolicies().getToken()).isEqualTo("test-policy-token");
        assertThat(properties.getSiteValues().getToken()).isNull();
    }

    @Test
    void readsPoliciesFromPolicyRepositoryAndValuesFromSiteRepository() throws Exception {
        AutoShiftRepository repository = new AutoShiftRepository(new RepositorySourceFactory(properties()), new YamlSupport());

        assertThat(repository.policies())
                .extracting("name")
                .containsExactlyInAnyOrder(
                        "excluded-policy",
                        "openshift-gitops",
                        "tempo");
        assertThat(repository.policiesRoot().toString()).endsWith("policies");
        assertThat(repository.siteValuesRoot().toString()).endsWith("autoshift");
        assertThat(repository.clusterSets()).extracting("name").containsExactly("managed", "sbx");
        assertThat(repository.clusters()).extracting("name").containsExactly("cluster-a", "cluster-b");
        assertThat(repository.policies().get(0).excluded()).isTrue();
        assertThat(repository.policies().stream()
                .filter(p -> p.name().equals("tempo"))
                .findFirst()
                .orElseThrow()
                .yaml())
                .contains("kind: Placement", "policy-generator-config.yaml", "kind: PolicyGenerator");
    }

    @Test
    void preservesDuplicateClusterSetNamesAcrossValuesFiles() throws Exception {
        Path root = Files.createTempDirectory("autoshift-test-");
        Path dir = root.resolve("autoshift/clustersets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("profile-a.yaml"), """
                managedClusterSets:
                  hub:
                    labels:
                      profile: 'a'
                """);
        Files.writeString(dir.resolve("profile-b.yaml"), """
                managedClusterSets:
                  hub:
                    labels:
                      profile: 'b'
                """);

        AutoShiftProperties properties = new AutoShiftProperties();
        properties.getPolicies().setLocation(root.toString());
        properties.getSiteValues().setLocation(root.toString());
        AutoShiftRepository repository = new AutoShiftRepository(new RepositorySourceFactory(properties), new YamlSupport());

        assertThat(repository.clusterSets()).extracting(com.redhat.autoshift.report.model.ClusterSet::id)
                .containsExactly("profile-a.yaml:managedClusterSets/hub", "profile-b.yaml:managedClusterSets/hub");
    }

    @Test
    void preservesDuplicateClusterNamesAcrossValuesFiles() throws Exception {
        Path root = Files.createTempDirectory("autoshift-test-");
        Path dir = root.resolve("autoshift/clusters");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("profile-a.yaml"), """
                clusters:
                  spoke-01:
                    config:
                      clusterSet: managed
                """);
        Files.writeString(dir.resolve("profile-b.yaml"), """
                clusters:
                  spoke-01:
                    config:
                      clusterSet: sbx
                """);

        AutoShiftProperties properties = new AutoShiftProperties();
        properties.getPolicies().setLocation(root.toString());
        properties.getSiteValues().setLocation(root.toString());
        AutoShiftRepository repository = new AutoShiftRepository(new RepositorySourceFactory(properties), new YamlSupport());

        assertThat(repository.clusters()).extracting(com.redhat.autoshift.report.model.Cluster::id)
                .containsExactly("profile-a.yaml:spoke-01", "profile-b.yaml:spoke-01");
    }


    @Test
    void resolvesPolicyDirectoryWhenConfiguredAtPoliciesRoot() throws Exception {
        Path root = Files.createTempDirectory("autoshift-policies-");
        Path policies = root.resolve("policies");
        Files.createDirectories(policies.resolve("stable"));
        Files.createDirectories(policies.resolve("certified"));
        Files.createDirectories(policies.resolve("community"));

        AutoShiftProperties p = new AutoShiftProperties();
        p.getPolicies().setLocation(policies.toString());

        AutoShiftRepository repository =
                new AutoShiftRepository(new RepositorySourceFactory(p), new YamlSupport());

        assertThat(repository.policiesRoot()).isEqualTo(policies);
    }

    @Test
    void resolvesSiteValuesUsingExplicitRepositoryPath() throws Exception {
        Path root = Files.createTempDirectory("autoshift-values-path-");
        Path values = root.resolve("config/site-values");
        Files.createDirectories(values.resolve("clusters"));
        Files.createDirectories(values.resolve("clustersets"));
        Files.writeString(values.resolve("global.yaml"), "excludePolicies: []\n");

        AutoShiftProperties p = new AutoShiftProperties();
        p.getSiteValues().setLocation(root.toString());
        p.getSiteValues().setPath("config/site-values");

        AutoShiftRepository repository =
                new AutoShiftRepository(new RepositorySourceFactory(p), new YamlSupport());

        assertThat(repository.siteValuesRoot()).isEqualTo(values);
    }

    @Test
    void repositoryPathCannotEscapeLocalRepositoryRoot() {
        AutoShiftProperties p = new AutoShiftProperties();
        p.getSiteValues().setLocation("/tmp/site-values");
        p.getSiteValues().setPath("../outside");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RepositorySourceFactory(p).siteValues())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not escape the repository root");
    }

    @Test
    void readsClustersAndClusterSetsFromEnvironmentSubdirectories() throws Exception {
        Path root = Files.createTempDirectory("autoshift-environments-");
        Path values = root.resolve("autoshift");
        Files.createDirectories(values.resolve("clusters/dev"));
        Files.createDirectories(values.resolve("clusters/qa"));
        Files.createDirectories(values.resolve("clustersets/dev"));
        Files.createDirectories(values.resolve("clustersets/qa"));

        Files.writeString(values.resolve("clusters/dev/clusters.yaml"), """
                clusters:
                  dev-01:
                    config:
                      clusterSet: managed
                """);
        Files.writeString(values.resolve("clusters/qa/clusters.yaml"), """
                clusters:
                  qa-01:
                    config:
                      clusterSet: managed
                """);
        Files.writeString(values.resolve("clustersets/dev/managed.yaml"), """
                managedClusterSets:
                  managed:
                    labels:
                      environment: dev
                """);
        Files.writeString(values.resolve("clustersets/qa/managed.yaml"), """
                managedClusterSets:
                  managed:
                    labels:
                      environment: qa
                """);

        AutoShiftProperties p = new AutoShiftProperties();
        p.getSiteValues().setLocation(root.toString());

        AutoShiftRepository repository = new AutoShiftRepository(
                new RepositorySourceFactory(p), new YamlSupport());

        assertThat(repository.clusters()).extracting(com.redhat.autoshift.report.model.Cluster::sourceName)
                .containsExactly("dev/clusters.yaml", "qa/clusters.yaml");
        assertThat(repository.clusterSets()).extracting(com.redhat.autoshift.report.model.ClusterSet::sourceName)
                .containsExactly("dev/managed.yaml", "qa/managed.yaml");
    }

    @Test
    void resolvesSiteValuesDirectoryWhenConfiguredAtValuesRoot() throws Exception {
        Path root = Files.createTempDirectory("autoshift-values-");
        Path values = root.resolve("autoshift");
        Files.createDirectories(values.resolve("clusters"));
        Files.createDirectories(values.resolve("clustersets"));

        AutoShiftProperties p = new AutoShiftProperties();
        p.getSiteValues().setLocation(values.toString());

        AutoShiftRepository repository =
                new AutoShiftRepository(new RepositorySourceFactory(p), new YamlSupport());

        assertThat(repository.siteValuesRoot()).isEqualTo(values);
    }
}
