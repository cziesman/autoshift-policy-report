package com.redhat.autoshift.report.controller;

import com.redhat.autoshift.report.service.PolicyReportService;
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
    public String policies(Model model) throws Exception {

        model.addAttribute("report", service.report());
        model.addAttribute("currentPage", "policies");
        return "policies";
    }

    @GetMapping("/policies/{name}")
    public String policy(@PathVariable String name, Model model) throws Exception {

        var summary = service.policy(name);
        if (summary == null) {
            return "redirect:/policies";
        }
        model.addAttribute("summary", summary);
        model.addAttribute("currentPage", "policies");
        return "policy";
    }

}
