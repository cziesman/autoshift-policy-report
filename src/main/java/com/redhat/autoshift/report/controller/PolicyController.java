package com.redhat.autoshift.report.controller;

import com.redhat.autoshift.report.service.PolicyReportService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PolicyController {

    @Autowired
    private PolicyReportService service;

    @GetMapping("/policies")
    public String policies(Model model, HttpSession session) throws Exception {
        String policyBranch = DashboardController.selectedPolicyBranch(session);
        String siteValuesBranch = DashboardController.selectedSiteValuesBranch(session);
        model.addAttribute("report", service.report(policyBranch, siteValuesBranch));
        model.addAttribute("currentPage", "policies");
        model.addAttribute("selectedPolicyBranch", policyBranch);
        model.addAttribute("selectedSiteValuesBranch", siteValuesBranch);
        return "policies";
    }

    @GetMapping("/policies/{name}")
    public String policy(
            @PathVariable String name,
            Model model,
            HttpSession session) throws Exception {

        String policyBranch = DashboardController.selectedPolicyBranch(session);
        String siteValuesBranch = DashboardController.selectedSiteValuesBranch(session);
        var summary = service.policy(name, policyBranch, siteValuesBranch);
        if (summary == null) {
            return "redirect:/policies";
        }
        model.addAttribute("summary", summary);
        model.addAttribute("currentPage", "policies");
        model.addAttribute("selectedPolicyBranch", policyBranch);
        model.addAttribute("selectedSiteValuesBranch", siteValuesBranch);
        return "policy";
    }
}
