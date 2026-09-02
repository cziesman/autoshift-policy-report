package com.redhat.autoshift.report.controller;

import com.redhat.autoshift.report.service.PolicyReportService;
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
    public String clusters(Model model) throws Exception {

        model.addAttribute("report", service.report());
        model.addAttribute("currentPage", "clusters");
        return "clusters";
    }

    @GetMapping("/clusters/{source}/{name}")
    public String cluster(@PathVariable String source, @PathVariable String name, Model model) throws Exception {

        var report = service.cluster(source, name);
        if (report == null) {
            return "redirect:/clusters";
        }
        model.addAttribute("report", report);
        model.addAttribute("currentPage", "clusters");
        return "cluster";
    }

}
