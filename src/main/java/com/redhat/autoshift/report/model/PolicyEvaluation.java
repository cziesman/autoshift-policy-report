package com.redhat.autoshift.report.model;

public record PolicyEvaluation(PolicyDefinition policy, PolicyState state, String source, String labelValue) {

}
