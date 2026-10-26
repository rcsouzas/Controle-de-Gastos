package br.com.controlegastos.repository;

import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.model.Transaction.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByDateBetweenOrderByDateDesc(LocalDate start, LocalDate end);

    List<Transaction> findByCategoryOrderByDateDesc(Category category);

    List<Transaction> findByDateBetweenAndCategoryOrderByDateDesc(LocalDate start, LocalDate end, Category category);

    Optional<Transaction> findByRecurringTransactionIdAndRecurrenceDate(Long recurringTransactionId, LocalDate recurrenceDate);

    List<Transaction> findByInstallmentGroupId(String installmentGroupId);

    List<Transaction> findByCategoryAndInstallmentGroupIdIsNullOrderByDateAsc(Category category);
}