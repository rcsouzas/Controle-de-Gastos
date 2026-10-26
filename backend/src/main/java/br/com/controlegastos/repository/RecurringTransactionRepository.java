package br.com.controlegastos.repository;

import br.com.controlegastos.model.RecurringTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, Long> {
    List<RecurringTransaction> findByActiveTrueOrderByStartDateAsc();
}
