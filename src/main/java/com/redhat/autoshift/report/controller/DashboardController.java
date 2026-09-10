package com.redhat.autoshift.report.controller;

import java.util.Set;

import com.redhat.autoshift.report.model.Report;
import com.redhat.autoshift.report.service.PolicyReportService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);
    private static final String SESSION_POLICY_BRANCH = "selectedPolicyBranch";
    private static final String SESSION_SITE_VALUES_BRANCH = "selectedSiteValuesBranch";
    private static final String DEFAULT_BRANCH = "main";

    @Autowired
    private PolicyReportService service;

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String policyBranch,
            @RequestParam(required = false) String siteValuesBranch,
            HttpSession session,
            Model model) throws Exception {

        Set<String> availablePolicyBranches = service.availablePolicyBranches();
        Set<String> availableSiteValuesBranches = service.availableSiteValuesBranches();

        String selectedPolicyBranch = resolveBranch(
                policyBranch, session.getAttribute(SESSION_POLICY_BRANCH),
                availablePolicyBranches);
        String selectedSiteValuesBranch = resolveBranch(
                siteValuesBranch, session.getAttribute(SESSION_SITE_VALUES_BRANCH),
                availableSiteValuesBranches);

        session.setAttribute(SESSION_POLICY_BRANCH, selectedPolicyBranch);
        session.setAttribute(SESSION_SITE_VALUES_BRANCH, selectedSiteValuesBranch);

        Report report = service.report(selectedPolicyBranch, selectedSiteValuesBranch);
        LOG.info("GET / - policyBranch={}, siteValuesBranch={}, {} policies, {} clustersets, {} clusters",
                selectedPolicyBranch,
                selectedSiteValuesBranch,
                report.policies().size(),
                report.clusterSets().size(),
                report.clusters().size());

        model.addAttribute("report", report);
        model.addAttribute("currentPage", "summary");
        model.addAttribute("selectedPolicyBranch", selectedPolicyBranch);
        model.addAttribute("selectedSiteValuesBranch", selectedSiteValuesBranch);
        model.addAttribute("availablePolicyBranches", availablePolicyBranches);
        model.addAttribute("availableSiteValuesBranches", availableSiteValuesBranches);
        model.addAttribute("policiesRepository", service.policiesRepositoryInfo(selectedPolicyBranch));
        model.addAttribute("siteValuesRepository", service.siteValuesRepositoryInfo(selectedSiteValuesBranch));
        return "index";
    }

    private String resolveBranch(String requested, Object sessionValue, Set<String> available) {
        String branch = requested != null && !requested.isBlank()
                ? requested
                : sessionValue == null ? DEFAULT_BRANCH : sessionValue.toString();

        if (available.contains(branch)) {
            return branch;
        }
        return available.contains(DEFAULT_BRANCH)
                ? DEFAULT_BRANCH
                : available.iterator().next();
    }

    static String selectedPolicyBranch(HttpSession session) {
        Object value = session.getAttribute(SESSION_POLICY_BRANCH);
        return value == null ? DEFAULT_BRANCH : value.toString();
    }

    static String selectedSiteValuesBranch(HttpSession session) {
        Object value = session.getAttribute(SESSION_SITE_VALUES_BRANCH);
        return value == null ? DEFAULT_BRANCH : value.toString();
    }
}
