package br.com.controlegastos.controller;

import br.com.controlegastos.model.FinancialProjection;
import br.com.controlegastos.service.PlanningService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/projection")
public class ProjectionController {
    private final PlanningService planningService;

    public ProjectionController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @GetMapping
    public FinancialProjection projectThrough(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return planningService.projectThrough(through);
    }
}
