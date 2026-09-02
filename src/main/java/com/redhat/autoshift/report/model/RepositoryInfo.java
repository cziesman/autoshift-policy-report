package com.redhat.autoshift.report.model;

/**
 * Repository information intended for display. Temporary checkout paths are deliberately not exposed.
 */
public record RepositoryInfo(
        String configuredLocation,
        String branch,
        String contentPath) {

}
