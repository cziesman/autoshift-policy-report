package com.redhat.autoshift.report;

import com.redhat.autoshift.report.config.AutoShiftProperties;
import com.redhat.autoshift.report.model.PolicyState;
import com.redhat.autoshift.report.repository.AutoShiftRepository;
import com.redhat.autoshift.report.repository.RepositorySourceFactory;
import com.redhat.autoshift.report.repository.YamlSupport;
import com.redhat.autoshift.report.resolver.DefaultPolicyResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyResolverTest {
    @Test
    void clusterLabelsOverrideClusterSetLabels() throws Exception {
        AutoShiftProperties properties = new AutoShiftProperties();
        properties.getPolicies().setLocation(Paths.get("src/test/resources/policy-repo-sample").toAbsolutePath().toString());
        properties.getSiteValues().setLocation(Paths.get("src/test/resources/site-values-sample").toAbsolutePath().toString());
        AutoShiftRepository repository = new AutoShiftRepository(new RepositorySourceFactory(properties), new YamlSupport());
        var clusters = repository.clusters();
        var sets = repository.clusterSets();
        var policies = repository.policies();
        var resolver = new DefaultPolicyResolver();
        var set = sets.stream().filter(s -> s.name().equals("managed")).findFirst().orElseThrow();
        var cluster = clusters.stream().filter(c -> c.name().equals("cluster-a")).findFirst().orElseThrow();

        var report = resolver.clusterReport(cluster, set, policies, clusters);
        var tempo = report.policies().stream().filter(p -> p.policy().name().equals("tempo")).findFirst().orElseThrow();
        assertThat(tempo.state()).isEqualTo(PolicyState.ENABLED);
        assertThat(tempo.source()).isEqualTo("ClusterSet: managed");
        var excluded = report.policies().stream().filter(p -> p.policy().name().equals("excluded-policy")).findFirst().orElseThrow();
        assertThat(excluded.state()).isEqualTo(PolicyState.EXCLUDED);
        assertThat(excluded.source()).isEqualTo("global excludePolicies");
    }
}
