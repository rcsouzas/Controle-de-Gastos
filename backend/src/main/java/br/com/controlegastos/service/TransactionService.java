package br.com.controlegastos.service;

import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.model.Transaction.Category;
import br.com.controlegastos.model.Transaction.TransactionType;
import br.com.controlegastos.model.FinancialSummary;
import br.com.controlegastos.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class TransactionService {
    private final TransactionRepository repository;

    public TransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Transaction> findAll(Integer year, Integer month, Category category) {
        LocalDate start = null;
        LocalDate end = null;
        if (year != null && month != null) {
            LocalDate firstDay = LocalDate.of(year, month, 1);
            start = firstDay;
            end = firstDay.withDayOfMonth(firstDay.lengthOfMonth());
        } else if (year != null) {
            start = LocalDate.of(year, 1, 1);
            end = LocalDate.of(year, 12, 31);
        } else if (month != null) {
            throw new IllegalArgumentException("Informe o ano ao filtrar por mês");
        }

        if (start != null && category != null) {
            return repository.findByDateBetweenAndCategoryOrderByDateDesc(start, end, category);
        }
        if (start != null) {
            return repository.findByDateBetweenOrderByDateDesc(start, end);
        }
        if (category != null) {
            return repository.findByCategoryOrderByDateDesc(category);
        }
        return repository.findAll().stream()
                .sorted((left, right) -> right.getDate().compareTo(left.getDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public FinancialSummary getSummary() {
        LocalDate today = LocalDate.now();
        List<Transaction> transactions = repository.findAll().stream()
                .filter(transaction -> !transaction.isPlanned() && !transaction.getDate().isAfter(today))
                .toList();
        BigDecimal totalIncome = sumByType(transactions, TransactionType.INCOME);
        BigDecimal totalExpenses = sumByType(transactions, TransactionType.EXPENSE);
        return new FinancialSummary(totalIncome.subtract(totalExpenses), totalIncome, totalExpenses);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalBalance() {
        return getSummary().totalBalance();
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalIncome() {
        return getSummary().totalIncome();
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalExpenses() {
        return getSummary().totalExpenses();
    }

    @Transactional
    public Transaction create(Transaction transaction) {
        transaction.setId(null);
        transaction.setRecurringTransactionId(null);
        transaction.setRecurrenceDate(null);
        return repository.saveAndFlush(transaction);
    }

    @Transactional
    public Transaction update(Long id, Transaction changes) {
        Transaction current = getById(id);
        if (current.getRecurringTransactionId() != null
                && !current.getRecurrenceDate().equals(changes.getDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data da ocorrência recorrente não pode ser alterada");
        }
        current.setDescription(changes.getDescription());
        current.setAmount(changes.getAmount());
        current.setDate(changes.getDate());
        current.setType(changes.getType());
        current.setCategory(changes.getCategory());
        current.setPaymentMethod(changes.getPaymentMethod());
        current.setPlanned(changes.isPlanned());
        return repository.saveAndFlush(current);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(getById(id));
    }

    private Transaction getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transação não encontrada"));
    }

    private BigDecimal sumByType(List<Transaction> transactions, TransactionType type) {
        return transactions.stream()
                .filter(transaction -> transaction.getType() == type)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}