package com.redhat.autoshift.report.model;

import java.nio.file.Path;
import java.util.List;

public record PolicyDefinition(
        String name,
        PolicyTier tier,
        Path directory,
        List<PolicyRule> rules,
        boolean excluded,
        String yaml) {

}
