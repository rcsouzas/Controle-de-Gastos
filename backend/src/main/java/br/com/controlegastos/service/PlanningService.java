package br.com.controlegastos.service;

import br.com.controlegastos.model.FinancialProjection;
import br.com.controlegastos.model.FinancialSummary;
import br.com.controlegastos.model.RecurringOccurrence;
import br.com.controlegastos.model.RecurringTransaction;
import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.model.Transaction.TransactionType;
import br.com.controlegastos.repository.RecurringTransactionRepository;
import br.com.controlegastos.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

@Service
@Transactional
public class PlanningService {
    private final RecurringTransactionRepository recurringRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

    public PlanningService(
            RecurringTransactionRepository recurringRepository,
            TransactionRepository transactionRepository,
            TransactionService transactionService) {
        this.recurringRepository = recurringRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
    }

    @Transactional(readOnly = true)
    public List<RecurringTransaction> findActiveRecurringTransactions() {
        return recurringRepository.findByActiveTrueOrderByStartDateAsc();
    }

    public RecurringTransaction createRecurringTransaction(RecurringTransaction recurring) {
        recurring.setId(null);
        validateRecurring(recurring);
        transactionService.validateChoices(recurring.getType(), recurring.getCategory(), recurring.getPaymentMethod());
        recurring.setActive(true);
        return recurringRepository.saveAndFlush(recurring);
    }

    public RecurringTransaction updateRecurringTransaction(Long id, RecurringTransaction changes) {
        RecurringTransaction current = getRecurringById(id);
        validateRecurring(changes);
        transactionService.validateChoices(changes.getType(), changes.getCategory(), changes.getPaymentMethod());
        current.setDescription(changes.getDescription());
        current.setAmount(changes.getAmount());
        current.setStartDate(changes.getStartDate());
        current.setEndDate(changes.getEndDate());
        current.setFrequency(changes.getFrequency());
        current.setType(changes.getType());
        current.setCategory(changes.getCategory());
        current.setPaymentMethod(changes.getPaymentMethod());
        return recurringRepository.saveAndFlush(current);
    }

    public void deactivateRecurringTransaction(Long id) {
        RecurringTransaction recurring = getRecurringById(id);
        recurring.setActive(false);
        recurringRepository.save(recurring);
    }

    public Transaction recordOccurrence(Long recurringId, Transaction transaction) {
        RecurringTransaction recurring = getRecurringById(recurringId);
        transactionService.validateChoices(transaction.getType(), transaction.getCategory(), transaction.getPaymentMethod());
        LocalDate date = transaction.getDate();
        if (!occursOn(recurring, date)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data não pertence a esta recorrência");
        }
        if (transactionRepository.findByRecurringTransactionIdAndRecurrenceDate(recurringId, date).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta ocorrência já foi registrada");
        }

        transaction.setId(null);
        transaction.setRecurringTransactionId(recurringId);
        transaction.setRecurrenceDate(date);
        transaction.setInstallmentGroupId(null);
        return transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(readOnly = true)
    public FinancialProjection projectThrough(LocalDate through) {
        LocalDate today = LocalDate.now();
        if (through == null || through.isBefore(today.minusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data final deve ser hoje ou uma data futura");
        }
        LocalDate projectionThrough = through.isBefore(today) ? today : through;

        FinancialSummary summary = transactionService.getSummary();
        List<Transaction> transactions = transactionRepository.findAll();
        Set<String> registeredOccurrences = new HashSet<>();
        transactions.stream()
                .filter(item -> item.getRecurringTransactionId() != null && item.getRecurrenceDate() != null)
                .forEach(item -> registeredOccurrences.add(occurrenceKey(item.getRecurringTransactionId(), item.getRecurrenceDate())));

        BigDecimal expectedIncome = BigDecimal.ZERO;
        BigDecimal expectedExpenses = BigDecimal.ZERO;
        TreeMap<LocalDate, BigDecimal> dailyNet = new TreeMap<>();
        for (Transaction transaction : transactions) {
            if (transaction.getDate().isBefore(today) || transaction.getDate().isAfter(projectionThrough)) {
                continue;
            }
            if (!transaction.isPlanned() && !transaction.getDate().isAfter(today)) {
                continue;
            }
            if (transaction.getType() == TransactionType.INCOME) {
                expectedIncome = expectedIncome.add(transaction.getAmount());
                dailyNet.merge(transaction.getDate(), transaction.getAmount(), BigDecimal::add);
            } else {
                expectedExpenses = expectedExpenses.add(transaction.getAmount());
                dailyNet.merge(transaction.getDate(), transaction.getAmount().negate(), BigDecimal::add);
            }
        }

        List<RecurringOccurrence> occurrences = new ArrayList<>();
        for (RecurringTransaction recurring : recurringRepository.findByActiveTrueOrderByStartDateAsc()) {
            for (LocalDate date : datesBetween(recurring, today, projectionThrough)) {
                if (registeredOccurrences.contains(occurrenceKey(recurring.getId(), date))) {
                    continue;
                }
                occurrences.add(toOccurrence(recurring, date));
                if (recurring.getType() == TransactionType.INCOME) {
                    expectedIncome = expectedIncome.add(recurring.getAmount());
                    dailyNet.merge(date, recurring.getAmount(), BigDecimal::add);
                } else {
                    expectedExpenses = expectedExpenses.add(recurring.getAmount());
                    dailyNet.merge(date, recurring.getAmount().negate(), BigDecimal::add);
                }
            }
        }

        BigDecimal projectedBalance = summary.totalBalance().add(expectedIncome).subtract(expectedExpenses);
        BigDecimal cumulativeChange = BigDecimal.ZERO;
        BigDecimal lowestCumulativeChange = BigDecimal.ZERO;
        for (BigDecimal dailyChange : dailyNet.values()) {
            cumulativeChange = cumulativeChange.add(dailyChange);
            lowestCumulativeChange = lowestCumulativeChange.min(cumulativeChange);
        }
        BigDecimal lowestProjectedBalance = summary.totalBalance().add(lowestCumulativeChange);
        BigDecimal additionalBalanceNeeded = lowestProjectedBalance.signum() < 0
                ? lowestProjectedBalance.negate()
                : BigDecimal.ZERO;
        return new FinancialProjection(projectionThrough, summary.totalBalance(), projectedBalance,
                lowestProjectedBalance, additionalBalanceNeeded, expectedIncome, expectedExpenses,
                List.copyOf(occurrences));
    }

    private RecurringTransaction getRecurringById(Long id) {
        return recurringRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recorrência não encontrada"));
    }

    private void validateRecurring(RecurringTransaction recurring) {
        if (recurring.getStartDate() != null && recurring.getEndDate() != null
                && recurring.getEndDate().isBefore(recurring.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data final deve ser posterior à data inicial");
        }
    }

    private boolean occursOn(RecurringTransaction recurring, LocalDate date) {
        if (date.isBefore(recurring.getStartDate())
                || (recurring.getEndDate() != null && date.isAfter(recurring.getEndDate()))) {
            return false;
        }
        if (recurring.getFrequency() == RecurringTransaction.Frequency.WEEKLY) {
            return ChronoUnit.DAYS.between(recurring.getStartDate(), date) % 7 == 0;
        }
        LocalDate expected = YearMonth.from(date)
                .atDay(Math.min(recurring.getStartDate().getDayOfMonth(), YearMonth.from(date).lengthOfMonth()));
        return expected.equals(date) && !YearMonth.from(date).isBefore(YearMonth.from(recurring.getStartDate()));
    }

    private List<LocalDate> datesBetween(RecurringTransaction recurring, LocalDate from, LocalDate through) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = recurring.getStartDate().isAfter(from) ? recurring.getStartDate() : from;
        if (recurring.getFrequency() == RecurringTransaction.Frequency.WEEKLY) {
            long daysFromStart = ChronoUnit.DAYS.between(recurring.getStartDate(), date);
            long weeksToSkip = Math.max(0, (daysFromStart + 6) / 7);
            date = recurring.getStartDate().plusWeeks(weeksToSkip);
            while (date.isBefore(from)) {
                date = date.plusWeeks(1);
            }
            while (!date.isAfter(through) && (recurring.getEndDate() == null || !date.isAfter(recurring.getEndDate()))) {
                dates.add(date);
                date = date.plusWeeks(1);
            }
            return dates;
        }

        YearMonth month = YearMonth.from(date);
        while (!month.isAfter(YearMonth.from(through))) {
            LocalDate occurrence = month.atDay(Math.min(recurring.getStartDate().getDayOfMonth(), month.lengthOfMonth()));
            if (!occurrence.isBefore(recurring.getStartDate()) && !occurrence.isBefore(from)
                    && !occurrence.isAfter(through)
                    && (recurring.getEndDate() == null || !occurrence.isAfter(recurring.getEndDate()))) {
                dates.add(occurrence);
            }
            month = month.plusMonths(1);
        }
        return dates;
    }

    private RecurringOccurrence toOccurrence(RecurringTransaction recurring, LocalDate date) {
        return new RecurringOccurrence(recurring.getId(), recurring.getDescription(), recurring.getAmount(),
                date, recurring.getType(), recurring.getCategory(), recurring.getPaymentMethod());
    }

    private String occurrenceKey(Long recurringId, LocalDate date) {
        return recurringId + ":" + date;
    }
}
