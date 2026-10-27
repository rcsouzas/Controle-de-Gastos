package br.com.controlegastos.model;

import java.math.BigDecimal;
import java.util.List;

public record FinancialReport(
        String startMonth,
        String endMonth,
        BigDecimal totalBalance,
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        List<MonthlySummary> monthlyTotals) {

    public record MonthlySummary(
            String month,
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal totalBalance) {
    }
}
