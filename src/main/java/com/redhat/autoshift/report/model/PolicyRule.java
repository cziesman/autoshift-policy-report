package com.redhat.autoshift.report.model;

import java.util.Set;

public record PolicyRule(String labelKey, Set<String> clusterSets) {

}
