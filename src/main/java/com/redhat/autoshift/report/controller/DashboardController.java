package com.redhat.autoshift.report.controller;

import com.redhat.autoshift.report.model.Report;
import com.redhat.autoshift.report.service.PolicyReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @Autowired
    private PolicyReportService service;

    @GetMapping("/")
    public String index(Model model) throws Exception {

        Report report = service.report();
        model.addAttribute("report", report);
        model.addAttribute("currentPage", "summary");
        model.addAttribute("policiesRepository", service.policiesRepositoryInfo());
        model.addAttribute("siteValuesRepository", service.siteValuesRepositoryInfo());
        return "index";
    }

}
