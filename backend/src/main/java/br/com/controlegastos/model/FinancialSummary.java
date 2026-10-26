package br.com.controlegastos.model;

import java.math.BigDecimal;

public record FinancialSummary(
        BigDecimal totalBalance,
        BigDecimal totalIncome,
        BigDecimal totalExpenses) {
}