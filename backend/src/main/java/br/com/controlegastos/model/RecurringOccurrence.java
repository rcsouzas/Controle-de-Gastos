package br.com.controlegastos.model;

import br.com.controlegastos.model.Transaction.Category;
import br.com.controlegastos.model.Transaction.PaymentMethod;
import br.com.controlegastos.model.Transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringOccurrence(
        Long recurringTransactionId,
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        Category category,
        PaymentMethod paymentMethod) {
}
