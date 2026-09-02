package com.redhat.autoshift.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autoshift.report")
public class AutoShiftProperties {

    private RepositoryProperties policies = new RepositoryProperties();

    private RepositoryProperties siteValues = new RepositoryProperties();

    private boolean refreshOnRequest = true;

    private long cacheSeconds = 60;

    public RepositoryProperties getPolicies() {

        return policies;
    }

    public void setPolicies(RepositoryProperties policies) {

        this.policies = policies;
    }

    public RepositoryProperties getSiteValues() {

        return siteValues;
    }

    public void setSiteValues(RepositoryProperties siteValues) {

        this.siteValues = siteValues;
    }

    public boolean isRefreshOnRequest() {

        return refreshOnRequest;
    }

    public void setRefreshOnRequest(boolean refreshOnRequest) {

        this.refreshOnRequest = refreshOnRequest;
    }

    public long getCacheSeconds() {

        return cacheSeconds;
    }

    public void setCacheSeconds(long cacheSeconds) {

        this.cacheSeconds = cacheSeconds;
    }

    public static class RepositoryProperties {

        private String location = ".";

        private String branch = "main";

        public String getLocation() {

            return location;
        }

        public void setLocation(String location) {

            this.location = location;
        }

        public String getBranch() {

            return branch;
        }

        public void setBranch(String branch) {

            this.branch = branch;
        }

    }

}
