package br.com.controlegastos.controller;

import br.com.controlegastos.model.RecurringTransaction;
import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.service.PlanningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recurrences")
public class RecurringTransactionController {
    private final PlanningService planningService;

    public RecurringTransactionController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @GetMapping
    public List<RecurringTransaction> findAll() {
        return planningService.findActiveRecurringTransactions();
    }

    @PostMapping
    public ResponseEntity<RecurringTransaction> create(@Valid @RequestBody RecurringTransaction recurring) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(planningService.createRecurringTransaction(recurring));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringTransaction> update(
            @PathVariable Long id,
            @Valid @RequestBody RecurringTransaction recurring) {
        return ResponseEntity.ok(planningService.updateRecurringTransaction(id, recurring));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        planningService.deactivateRecurringTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/occurrences")
    public ResponseEntity<Transaction> recordOccurrence(
            @PathVariable Long id,
            @Valid @RequestBody Transaction transaction) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(planningService.recordOccurrence(id, transaction));
    }
}
