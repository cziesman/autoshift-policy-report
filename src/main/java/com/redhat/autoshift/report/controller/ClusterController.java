package com.redhat.autoshift.report.controller;

import com.redhat.autoshift.report.service.PolicyReportService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ClusterController {

    @Autowired
    private PolicyReportService service;

    @GetMapping("/clusters")
    public String clusters(Model model, HttpSession session) throws Exception {
        String policyBranch = DashboardController.selectedPolicyBranch(session);
        String siteValuesBranch = DashboardController.selectedSiteValuesBranch(session);
        model.addAttribute("report", service.report(policyBranch, siteValuesBranch));
        model.addAttribute("currentPage", "clusters");
        model.addAttribute("selectedPolicyBranch", policyBranch);
        model.addAttribute("selectedSiteValuesBranch", siteValuesBranch);
        return "clusters";
    }

    @GetMapping("/clusters/{source}/{name}")
    public String cluster(
            @PathVariable String source,
            @PathVariable String name,
            Model model,
            HttpSession session) throws Exception {

        String policyBranch = DashboardController.selectedPolicyBranch(session);
        String siteValuesBranch = DashboardController.selectedSiteValuesBranch(session);
        var report = service.cluster(source, name, policyBranch, siteValuesBranch);
        if (report == null) {
            return "redirect:/clusters";
        }
        model.addAttribute("report", report);
        model.addAttribute("currentPage", "clusters");
        model.addAttribute("selectedPolicyBranch", policyBranch);
        model.addAttribute("selectedSiteValuesBranch", siteValuesBranch);
        return "cluster";
    }
}
