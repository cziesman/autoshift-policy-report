package com.redhat.autoshift.report.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.redhat.autoshift.report.service.PolicyReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ClusterSetController {

    @Autowired
    private PolicyReportService service;

    @GetMapping("/clustersets")
    public String clusterSets(Model model) throws Exception {

        var report = service.report();
        Map<String, Long> clusterCounts = new LinkedHashMap<>();
        for (var cs : report.clusterSets()) {
            long count = report.clusters().stream()
                    .filter(c -> cs.name().equals(c.clusterSet()))
                    .filter(c -> cs.sourceName().equals(c.sourceName()) || uniqueClusterSetSource(report.clusters(), cs))
                    .count();
            clusterCounts.put(cs.id(), count);
        }
        model.addAttribute("report", report);
        model.addAttribute("currentPage", "clustersets");
        model.addAttribute("clusterCounts", clusterCounts);
        return "clustersets";
    }

    private boolean uniqueClusterSetSource(java.util.List<com.redhat.autoshift.report.model.Cluster> clusters, com.redhat.autoshift.report.model.ClusterSet cs) {

        return clusters.stream().filter(c -> cs.name().equals(c.clusterSet())).count() == 1;
    }

    @GetMapping("/clustersets/{type}/{source}/{name}")
    public String clusterSet(@PathVariable String type, @PathVariable String source, @PathVariable String name, Model model) throws Exception {

        var report = service.clusterSet(source, type, name);
        if (report == null) {
            return "redirect:/clustersets";
        }
        model.addAttribute("report", report);
        model.addAttribute("currentPage", "clustersets");
        return "clusterset";
    }

}
