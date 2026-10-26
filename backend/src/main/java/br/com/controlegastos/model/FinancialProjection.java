package br.com.controlegastos.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FinancialProjection(
        LocalDate through,
        BigDecimal currentBalance,
        BigDecimal projectedBalance,
        BigDecimal lowestProjectedBalance,
        BigDecimal additionalBalanceNeeded,
        BigDecimal expectedIncome,
        BigDecimal expectedExpenses,
        List<RecurringOccurrence> recurringOccurrences) {
}
