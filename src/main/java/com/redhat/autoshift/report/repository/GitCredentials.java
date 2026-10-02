package com.redhat.autoshift.report.repository;

/**
 * Credentials returned by a Git credential helper.
 */
record GitCredentials(String username, String password) {
}
