package br.com.controlegastos.service;

import br.com.controlegastos.model.FinancialReport;
import br.com.controlegastos.model.FinancialSummary;
import br.com.controlegastos.model.RecurringTransaction;
import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.model.Transaction.Category;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class TransactionService {
    private final TransactionRepository repository;
    private final RecurringTransactionRepository recurringRepository;

    public TransactionService(
            TransactionRepository repository, RecurringTransactionRepository recurringRepository) {
        this.repository = repository;
        this.recurringRepository = recurringRepository;
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
    public FinancialReport getReport(
            String requestedStartMonth, String requestedEndMonth, boolean incluirPlanejados) {
        YearMonth startMonth;
        YearMonth endMonth;
        try {
            startMonth = YearMonth.parse(requestedStartMonth);
            endMonth = YearMonth.parse(requestedEndMonth);
        } catch (java.time.DateTimeException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os meses no formato AAAA-MM");
        }
        if (startMonth.isAfter(endMonth)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O mês inicial deve ser anterior ou igual ao mês final");
        }

        LocalDate today = LocalDate.now();
        LocalDate startDate = startMonth.atDay(1);
        LocalDate endDate = endMonth.atEndOfMonth();
        List<Transaction> periodTransactions = repository.findByDateBetweenOrderByDateDesc(startDate, endDate);
        List<Transaction> transactions = periodTransactions.stream()
            .filter(transaction -> (transaction.isPlanned()
                && transaction.getType() == TransactionType.INCOME)
                || incluirPlanejados
                        || (!transaction.isPlanned() && !transaction.getDate().isAfter(today)))
                .toList();

        Map<YearMonth, BigDecimal[]> monthlyTotals = new HashMap<>();
        for (YearMonth month = startMonth; !month.isAfter(endMonth); month = month.plusMonths(1)) {
            monthlyTotals.put(month, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
        }
        for (Transaction transaction : transactions) {
            BigDecimal[] totals = monthlyTotals.get(YearMonth.from(transaction.getDate()));
            int amountIndex = transaction.getType() == TransactionType.INCOME ? 0 : 1;
            totals[amountIndex] = totals[amountIndex].add(transaction.getAmount());
        }
        addProjectedRecurringIncome(monthlyTotals, periodTransactions, startMonth, endMonth, today);

        BigDecimal totalIncome = monthlyTotals.values().stream()
                .map(totals -> totals[0])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = monthlyTotals.values().stream()
                .map(totals -> totals[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<FinancialReport.MonthlySummary> monthlySummaries = monthlyTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new FinancialReport.MonthlySummary(
                        entry.getKey().toString(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[0].subtract(entry.getValue()[1])))
                .toList();

        return new FinancialReport(
                startMonth.toString(),
                endMonth.toString(),
                totalIncome.subtract(totalExpenses),
                totalIncome,
                totalExpenses,
                monthlySummaries);
    }

    private void addProjectedRecurringIncome(
            Map<YearMonth, BigDecimal[]> monthlyTotals,
            List<Transaction> periodTransactions,
            YearMonth startMonth,
            YearMonth endMonth,
            LocalDate today) {
        Set<String> registeredOccurrences = new HashSet<>();
        periodTransactions.stream()
                .filter(transaction -> transaction.getRecurringTransactionId() != null
                        && transaction.getRecurrenceDate() != null)
                .forEach(transaction -> registeredOccurrences.add(
                        transaction.getRecurringTransactionId() + ":" + transaction.getRecurrenceDate()));

        LocalDate reportStart = startMonth.atDay(1);
        LocalDate reportEnd = endMonth.atEndOfMonth();
        for (RecurringTransaction recurring : recurringRepository.findByActiveTrueOrderByStartDateAsc()) {
            if (recurring.getType() != TransactionType.INCOME) {
                continue;
            }
            LocalDate firstDate = recurring.getStartDate().isAfter(reportStart)
                    ? recurring.getStartDate() : reportStart;
            if (firstDate.isBefore(today)) {
                firstDate = today;
            }
            LocalDate lastDate = recurring.getEndDate() != null && recurring.getEndDate().isBefore(reportEnd)
                    ? recurring.getEndDate() : reportEnd;
            if (firstDate.isAfter(lastDate)) {
                continue;
            }

            if (recurring.getFrequency() == RecurringTransaction.Frequency.WEEKLY) {
                long daysSinceStart = ChronoUnit.DAYS.between(recurring.getStartDate(), firstDate);
                long daysToOccurrence = (7 - daysSinceStart % 7) % 7;
                for (LocalDate date = firstDate.plusDays(daysToOccurrence);
                        !date.isAfter(lastDate); date = date.plusWeeks(1)) {
                    addProjectedIncome(monthlyTotals, registeredOccurrences, recurring, date);
                }
            } else {
                for (YearMonth month = YearMonth.from(firstDate);
                        !month.isAfter(YearMonth.from(lastDate)); month = month.plusMonths(1)) {
                    LocalDate date = month.atDay(Math.min(
                            recurring.getStartDate().getDayOfMonth(), month.lengthOfMonth()));
                    if (!date.isBefore(firstDate) && !date.isAfter(lastDate)) {
                        addProjectedIncome(monthlyTotals, registeredOccurrences, recurring, date);
                    }
                }
            }
        }
    }

    private void addProjectedIncome(
            Map<YearMonth, BigDecimal[]> monthlyTotals,
            Set<String> registeredOccurrences,
            RecurringTransaction recurring,
            LocalDate date) {
        String occurrenceKey = recurring.getId() + ":" + date;
        if (registeredOccurrences.contains(occurrenceKey)) {
            return;
        }
        BigDecimal[] totals = monthlyTotals.get(YearMonth.from(date));
        totals[0] = totals[0].add(recurring.getAmount());
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
        validateChoices(transaction.getType(), transaction.getCategory(), transaction.getPaymentMethod());
        transaction.setId(null);
        transaction.setRecurringTransactionId(null);
        transaction.setRecurrenceDate(null);
        transaction.setInstallmentGroupId(null);
        return repository.saveAndFlush(transaction);
    }

    @Transactional
    public List<Transaction> createInstallments(Transaction transaction, int count) {
        if (count < 1 || count > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade deve estar entre 1 e 120 parcelas");
        }
        if (transaction.getCategory() != Category.INSTALLMENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A categoria deve ser Parcela");
        }
        validateChoices(transaction.getType(), transaction.getCategory(), transaction.getPaymentMethod());

        LocalDate startDate = transaction.getDate();
        String installmentGroupId = UUID.randomUUID().toString();
        List<Transaction> installments = new ArrayList<>(count);
        for (int installmentNumber = 0; installmentNumber < count; installmentNumber++) {
            YearMonth installmentMonth = YearMonth.from(startDate).plusMonths(installmentNumber);
            LocalDate installmentDate = installmentMonth.atDay(
                    Math.min(startDate.getDayOfMonth(), installmentMonth.lengthOfMonth()));

            Transaction installment = new Transaction();
            installment.setDescription(transaction.getDescription());
            installment.setAmount(transaction.getAmount());
            installment.setDate(installmentDate);
            installment.setType(transaction.getType());
            installment.setCategory(transaction.getCategory());
            installment.setPaymentMethod(transaction.getPaymentMethod());
            installment.setPlanned(transaction.isPlanned() || installmentDate.isAfter(LocalDate.now()));
            installment.setInstallmentGroupId(installmentGroupId);
            installments.add(installment);
        }
        return repository.saveAllAndFlush(installments);
    }

    @Transactional
    public Transaction update(Long id, Transaction changes) {
        validateChoices(changes.getType(), changes.getCategory(), changes.getPaymentMethod());
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
        Transaction transaction = getById(id);
        if (transaction.getInstallmentGroupId() != null) {
            repository.deleteAll(repository.findByInstallmentGroupId(transaction.getInstallmentGroupId()));
            return;
        }
        if (transaction.getCategory() == Category.INSTALLMENT) {
            List<Transaction> legacyGroup = findLegacyInstallmentGroup(transaction);
            if (legacyGroup.size() > 1) {
                repository.deleteAll(legacyGroup);
                return;
            }
        }
        repository.delete(transaction);
    }

    private List<Transaction> findLegacyInstallmentGroup(Transaction target) {
        Map<LocalDate, List<Transaction>> candidatesByDate = new HashMap<>();
        for (Transaction candidate : repository.findByCategoryAndInstallmentGroupIdIsNullOrderByDateAsc(Category.INSTALLMENT)) {
            if (candidate.getDescription().equals(target.getDescription())
                    && candidate.getAmount().compareTo(target.getAmount()) == 0
                    && candidate.getType() == target.getType()
                    && candidate.getPaymentMethod() == target.getPaymentMethod()) {
                candidatesByDate.computeIfAbsent(candidate.getDate(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        if (candidatesByDate.getOrDefault(target.getDate(), List.of()).size() != 1) {
            return List.of(target);
        }

        List<Transaction> longestGroup = List.of();
        boolean ambiguousLongestGroup = false;
        for (LocalDate startDate : candidatesByDate.keySet()) {
            if (startDate.isAfter(target.getDate()) || candidatesByDate.get(startDate).size() != 1) {
                continue;
            }
            int anchorDay = startDate.getDayOfMonth();
            List<Transaction> group = new ArrayList<>();
            for (int monthOffset = 0; monthOffset < 120; monthOffset++) {
                YearMonth month = YearMonth.from(startDate).plusMonths(monthOffset);
                LocalDate installmentDate = month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
                List<Transaction> matchingDate = candidatesByDate.getOrDefault(installmentDate, List.of());
                if (matchingDate.size() != 1) {
                    break;
                }
                group.add(matchingDate.get(0));
            }
            if (group.size() > longestGroup.size() && group.stream().anyMatch(item -> item.getId().equals(target.getId()))) {
                longestGroup = group;
                ambiguousLongestGroup = false;
            } else if (group.size() == longestGroup.size()
                    && group.size() > 1
                    && group.stream().anyMatch(item -> item.getId().equals(target.getId()))
                    && !group.equals(longestGroup)) {
                ambiguousLongestGroup = true;
            }
        }
        return longestGroup.size() > 1 && !ambiguousLongestGroup ? longestGroup : List.of(target);
    }

    void validateChoices(TransactionType type, Category category, Transaction.PaymentMethod paymentMethod) {
        boolean validCategory = type == TransactionType.EXPENSE
                ? category == Category.FOOD || category == Category.HOUSING || category == Category.LEISURE
                        || category == Category.TRANSPORT || category == Category.INVESTMENTS
                        || category == Category.OTHER || category == Category.SUBSCRIPTION
                        || category == Category.INSTALLMENT || category == Category.INVOICE
                : category == Category.SALARY || category == Category.INVESTMENTS
                        || category == Category.OTHER || category == Category.REIMBURSEMENT;
        boolean validPaymentMethod = type == TransactionType.EXPENSE
                || paymentMethod == Transaction.PaymentMethod.PIX
                || paymentMethod == Transaction.PaymentMethod.BANK_TRANSFER
                || paymentMethod == Transaction.PaymentMethod.CASH
                || paymentMethod == Transaction.PaymentMethod.OTHER;
        if (!validCategory || !validPaymentMethod) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A categoria ou forma de pagamento não é compatível com o tipo de transação");
        }
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