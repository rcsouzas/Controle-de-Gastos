package br.com.controlegastos;

import br.com.controlegastos.config.CategorySchemaMigration;
import br.com.controlegastos.model.RecurringTransaction;
import br.com.controlegastos.model.Transaction;
import br.com.controlegastos.repository.RecurringTransactionRepository;
import br.com.controlegastos.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:planning-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class PlanningIntegrationTest {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RecurringTransactionRepository recurringRepository;

    @Autowired
    private CategorySchemaMigration categorySchemaMigration;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        recurringRepository.deleteAll();
    }

    @Test
    void projectedBalanceIncludesPlansAndUsesAnOccurrenceOverrideWithoutChangingActualSummary() {
        LocalDate today = LocalDate.now();
        LocalDate firstOccurrence = today.plusDays(1);
        LocalDate through = firstOccurrence.plusWeeks(2);
        Map<String, Object> recurring = Map.of(
                "description", "Renda semanal",
                "amount", 300,
                "startDate", firstOccurrence.toString(),
                "frequency", "WEEKLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");

        ResponseEntity<Map<String, Object>> createdRecurring = http.exchange(
                "/api/recurrences", HttpMethod.POST, jsonEntity(recurring),
                new ParameterizedTypeReference<>() {});
        assertThat(createdRecurring.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number recurringId = (Number) createdRecurring.getBody().get("id");

        Map<String, Object> plannedExpense = Map.of(
                "description", "Conta futura",
                "amount", 100,
                "date", firstOccurrence.plusDays(1).toString(),
                "type", "EXPENSE",
                "category", "HOUSING",
                "paymentMethod", "PIX",
                "planned", true);
        ResponseEntity<Map<String, Object>> createdExpense = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(plannedExpense),
                new ParameterizedTypeReference<>() {});
        assertThat(createdExpense.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> occurrenceOverride = Map.of(
                "description", "Renda semanal",
                "amount", 450,
                "date", firstOccurrence.toString(),
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX",
                "planned", true);
        ResponseEntity<Map<String, Object>> createdOccurrence = http.exchange(
                "/api/recurrences/" + recurringId.longValue() + "/occurrences",
                HttpMethod.POST, jsonEntity(occurrenceOverride),
                new ParameterizedTypeReference<>() {});
        assertThat(createdOccurrence.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> summary = http.exchange(
                "/api/transactions/summary", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(summary.getBody().get("totalBalance").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);

        ResponseEntity<Map<String, Object>> projection = http.exchange(
                "/api/projection?through=" + through, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(projection.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(projection.getBody().get("expectedIncome").toString()))
                .isEqualByComparingTo("1050");
        assertThat(new BigDecimal(projection.getBody().get("expectedExpenses").toString()))
                .isEqualByComparingTo("100");
        assertThat(new BigDecimal(projection.getBody().get("projectedBalance").toString()))
                .isEqualByComparingTo("950");
    }

    @Test
    void savingRecurringIncomeAndExpenseUpdatesProjectedBalance() {
        LocalDate today = LocalDate.now();
        LocalDate firstOccurrence = today.plusDays(1);
        LocalDate through = today.plusDays(8);

        Map<String, Object> recurringIncome = Map.of(
                "description", "Receita semanal",
                "amount", 300,
                "startDate", firstOccurrence.toString(),
                "frequency", "WEEKLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        Map<String, Object> recurringExpense = Map.of(
                "description", "Despesa semanal",
                "amount", 50,
                "startDate", firstOccurrence.toString(),
                "frequency", "WEEKLY",
                "type", "EXPENSE",
                "category", "HOUSING",
                "paymentMethod", "PIX");

        ResponseEntity<Map<String, Object>> beforeSave = http.exchange(
                "/api/projection?through=" + through, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(beforeSave.getBody().get("projectedBalance").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);

        http.exchange("/api/recurrences", HttpMethod.POST, jsonEntity(recurringIncome), Map.class);
        http.exchange("/api/recurrences", HttpMethod.POST, jsonEntity(recurringExpense), Map.class);

        ResponseEntity<Map<String, Object>> afterSave = http.exchange(
                "/api/projection?through=" + through, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(afterSave.getBody().get("expectedIncome").toString()))
                .isEqualByComparingTo("600");
        assertThat(new BigDecimal(afterSave.getBody().get("expectedExpenses").toString()))
                .isEqualByComparingTo("100");
        assertThat(new BigDecimal(afterSave.getBody().get("projectedBalance").toString()))
                .isEqualByComparingTo("500");
    }

    @Test
    void installmentTransactionsAreSavedMonthlyAndFutureAmountsEnterProjection() {
        LocalDate firstInstallment = LocalDate.of(2027, 1, 31);
        Map<String, Object> transaction = Map.of(
                "description", "Compra parcelada",
                "amount", 150,
                "date", firstInstallment.toString(),
                "type", "EXPENSE",
                "category", "INSTALLMENT",
                "paymentMethod", "CREDIT_CARD",
                "planned", false);
        Map<String, Object> request = Map.of("transaction", transaction, "count", 3);

        ResponseEntity<java.util.List<Map<String, Object>>> created = http.exchange(
                "/api/transactions/installments", HttpMethod.POST, jsonEntity(request),
                new ParameterizedTypeReference<>() {});

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).hasSize(3);
        assertThat(created.getBody()).extracting(item -> item.get("date"))
                .containsExactly("2027-01-31", "2027-02-28", "2027-03-31");
        assertThat(created.getBody()).allSatisfy(item -> {
            assertThat(item.get("category")).isEqualTo("INSTALLMENT");
            assertThat(item.get("planned")).isEqualTo(true);
            assertThat(item.get("installmentGroupId")).isNotNull();
        });
        assertThat(created.getBody()).extracting(item -> item.get("installmentGroupId"))
                .containsOnly(created.getBody().get(0).get("installmentGroupId"));

        ResponseEntity<Map<String, Object>> projection = http.exchange(
                "/api/projection?through=2027-03-31", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("expectedExpenses").toString()))
                .isEqualByComparingTo("450");
        assertThat(new BigDecimal(projection.getBody().get("projectedBalance").toString()))
                .isEqualByComparingTo("-450");

        ResponseEntity<Void> deleted = http.exchange(
                "/api/transactions/" + created.getBody().get(1).get("id"), HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(transactionRepository.count()).isZero();
        projection = http.exchange(
                "/api/projection?through=2027-03-31", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("expectedExpenses").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void subscriptionCategoryCanBeSaved() {
        Map<String, Object> subscription = Map.of(
                "description", "Assinatura mensal",
                "amount", 29.90,
                "date", LocalDate.now().toString(),
                "type", "EXPENSE",
                "category", "SUBSCRIPTION",
                "paymentMethod", "CREDIT_CARD",
                "planned", false);

        ResponseEntity<Map<String, Object>> created = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(subscription),
                new ParameterizedTypeReference<>() {});

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("category")).isEqualTo("SUBSCRIPTION");
    }

    @Test
    void deletingALegacyInstallmentRemovesItsMonthlySiblings() {
        LocalDate firstInstallment = LocalDate.of(2027, 1, 31);
        Transaction first = new Transaction();
        first.setDescription("Compra parcelada antiga");
        first.setAmount(new BigDecimal("100"));
        first.setDate(firstInstallment);
        first.setType(Transaction.TransactionType.EXPENSE);
        first.setCategory(Transaction.Category.INSTALLMENT);
        first.setPaymentMethod(Transaction.PaymentMethod.CREDIT_CARD);
        first.setPlanned(true);
        ResponseEntity<java.util.List<Map<String, Object>>> created = http.exchange(
                "/api/transactions/installments",
                HttpMethod.POST,
                jsonEntity(Map.of("transaction", Map.of(
                        "description", first.getDescription(),
                        "amount", first.getAmount(),
                        "date", first.getDate().toString(),
                        "type", first.getType().name(),
                        "category", first.getCategory().name(),
                        "paymentMethod", first.getPaymentMethod().name(),
                        "planned", true), "count", 3)),
                new ParameterizedTypeReference<>() {});
        assertThat(created.getBody()).hasSize(3);

        transactionRepository.findAll().forEach(transaction -> transaction.setInstallmentGroupId(null));
        transactionRepository.flush();

        ResponseEntity<Void> deleted = http.exchange(
                "/api/transactions/" + created.getBody().get(1).get("id"),
                HttpMethod.DELETE, null, Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void incomeAndExpenseRejectIncompatibleCategoriesAndPaymentMethods() {
        Map<String, Object> invalidIncome = Map.of(
                "description", "Assinatura como receita",
                "amount", 20,
                "date", LocalDate.now().toString(),
                "type", "INCOME",
                "category", "SUBSCRIPTION",
                "paymentMethod", "CREDIT_CARD",
                "planned", false);

        ResponseEntity<Map<String, String>> response = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(invalidIncome),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).contains("compatível");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void legacyH2CategoryEnumsAreMigratedWithoutLosingExistingTransactions() throws Exception {
        Map<String, Object> existingTransaction = Map.of(
                "description", "Categoria antiga",
                "amount", 10,
                "date", LocalDate.now().toString(),
                "type", "EXPENSE",
                "category", "FOOD",
                "paymentMethod", "PIX",
                "planned", false);
        http.exchange("/api/transactions", HttpMethod.POST, jsonEntity(existingTransaction), Map.class);

        String legacyCategories = "ENUM('FOOD','HOUSING','LEISURE','TRANSPORT','INVESTMENTS','OTHER','SALARY')";
        jdbcTemplate.execute("ALTER TABLE transactions ALTER COLUMN category SET DATA TYPE " + legacyCategories);
        jdbcTemplate.execute("ALTER TABLE recurring_transactions ALTER COLUMN category SET DATA TYPE " + legacyCategories);

        categorySchemaMigration.run(null);

        assertThat(transactionRepository.findAll()).hasSize(1);
        assertThat(transactionRepository.findAll().get(0).getCategory()).isEqualTo(Transaction.Category.FOOD);

        Map<String, Object> subscription = Map.of(
                "description", "Assinatura após migração",
                "amount", 29.90,
                "date", LocalDate.now().toString(),
                "type", "EXPENSE",
                "category", "SUBSCRIPTION",
                "paymentMethod", "CREDIT_CARD",
                "planned", false);
        ResponseEntity<Map<String, Object>> saved = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(subscription),
                new ParameterizedTypeReference<>() {});

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("category")).isEqualTo("SUBSCRIPTION");
    }

    @Test
    void confirmedTransactionsUpdateTheRealBalanceAndStopBeingProjected() {
        LocalDate today = LocalDate.now();
        Map<String, Object> recurring = Map.of(
                "description", "Pagamento mensal",
                "amount", 500,
                "startDate", today.toString(),
                "frequency", "MONTHLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");

        ResponseEntity<Map<String, Object>> createdRecurring = http.exchange(
                "/api/recurrences", HttpMethod.POST, jsonEntity(recurring),
                new ParameterizedTypeReference<>() {});
        Number recurringId = (Number) createdRecurring.getBody().get("id");
        Map<String, Object> confirmedOccurrence = Map.of(
                "description", "Pagamento mensal recebido",
                "amount", 525,
                "date", today.toString(),
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX",
                "planned", false);
        http.exchange("/api/recurrences/" + recurringId.longValue() + "/occurrences",
                HttpMethod.POST, jsonEntity(confirmedOccurrence), Map.class);

        ResponseEntity<Map<String, Object>> summary = http.exchange(
                "/api/transactions/summary", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(summary.getBody().get("totalIncome").toString()))
                .isEqualByComparingTo("525");

        ResponseEntity<Map<String, Object>> projection = http.exchange(
                "/api/projection?through=" + today, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("expectedIncome").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void transactionsCanBeCreatedUpdatedAndDeletedThroughTheApi() {
        Map<String, Object> transaction = Map.of(
                "description", "Pagamento",
                "amount", 100,
                "date", LocalDate.now().toString(),
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        ResponseEntity<Map<String, Object>> created = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(transaction),
                new ParameterizedTypeReference<>() {});
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("date")).isEqualTo(LocalDate.now().toString());
        assertThat(created.getBody().get("planned")).isEqualTo(false);
        Number id = (Number) created.getBody().get("id");

        Map<String, Object> updatedTransaction = Map.of(
                "description", "Pagamento atualizado",
                "amount", 175,
                "date", LocalDate.now().toString(),
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        ResponseEntity<Map<String, Object>> updated = http.exchange(
                "/api/transactions/" + id.longValue(), HttpMethod.PUT, jsonEntity(updatedTransaction),
                new ParameterizedTypeReference<>() {});
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(updated.getBody().get("amount").toString()))
                .isEqualByComparingTo("175");

        ResponseEntity<Map<String, Object>> summary = http.exchange(
                "/api/transactions/summary", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(summary.getBody().get("totalIncome").toString()))
                .isEqualByComparingTo("175");

        ResponseEntity<Void> deleted = http.exchange(
                "/api/transactions/" + id.longValue(), HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void invalidTransactionDateReturnsAnActionableJsonError() {
        Map<String, Object> transaction = Map.of(
                "description", "Data inválida",
                "amount", 100,
                "date", "28/11/2026",
                "type", "EXPENSE",
                "category", "OTHER",
                "paymentMethod", "PIX",
                "planned", false);

        ResponseEntity<Map<String, String>> response = http.exchange(
                "/api/transactions", HttpMethod.POST, jsonEntity(transaction),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).contains("yyyy-MM-dd");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void recurringSeriesCanBeUpdatedAndEndedWithoutLeavingFutureForecasts() {
        LocalDate firstOccurrence = LocalDate.now().plusDays(1);
        LocalDate through = firstOccurrence.plusWeeks(2);
        Map<String, Object> recurring = Map.of(
                "description", "Receita semanal",
                "amount", 300,
                "startDate", firstOccurrence.toString(),
                "frequency", "WEEKLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        ResponseEntity<Map<String, Object>> created = http.exchange(
                "/api/recurrences", HttpMethod.POST, jsonEntity(recurring),
                new ParameterizedTypeReference<>() {});
        Number id = (Number) created.getBody().get("id");

        Map<String, Object> updatedRecurring = Map.of(
                "description", "Receita semanal",
                "amount", 450,
                "startDate", firstOccurrence.toString(),
                "frequency", "WEEKLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        ResponseEntity<Map<String, Object>> updated = http.exchange(
                "/api/recurrences/" + id.longValue(), HttpMethod.PUT, jsonEntity(updatedRecurring),
                new ParameterizedTypeReference<>() {});
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> projection = http.exchange(
                "/api/projection?through=" + through, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("expectedIncome").toString()))
                .isEqualByComparingTo("1350");

        ResponseEntity<Void> ended = http.exchange(
                "/api/recurrences/" + id.longValue(), HttpMethod.DELETE, null, Void.class);
        assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        projection = http.exchange("/api/projection?through=" + through, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("expectedIncome").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void projectionReportsTheCashNeededBeforeLaterIncomeArrives() {
        LocalDate today = LocalDate.now();
        LocalDate expenseDate = today.plusDays(1);
        LocalDate incomeDate = today.plusDays(8);
        Map<String, Object> plannedExpense = Map.of(
                "description", "Despesa antes do pagamento",
                "amount", 500,
                "date", expenseDate.toString(),
                "type", "EXPENSE",
                "category", "HOUSING",
                "paymentMethod", "PIX",
                "planned", true);
        http.exchange("/api/transactions", HttpMethod.POST, jsonEntity(plannedExpense), Map.class);

        Map<String, Object> recurringIncome = Map.of(
                "description", "Pagamento posterior",
                "amount", 1000,
                "startDate", incomeDate.toString(),
                "frequency", "MONTHLY",
                "type", "INCOME",
                "category", "SALARY",
                "paymentMethod", "PIX");
        http.exchange("/api/recurrences", HttpMethod.POST, jsonEntity(recurringIncome), Map.class);

        ResponseEntity<Map<String, Object>> projection = http.exchange(
                "/api/projection?through=" + incomeDate, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(new BigDecimal(projection.getBody().get("projectedBalance").toString()))
                .isEqualByComparingTo("500");
        assertThat(new BigDecimal(projection.getBody().get("lowestProjectedBalance").toString()))
                .isEqualByComparingTo("-500");
        assertThat(new BigDecimal(projection.getBody().get("additionalBalanceNeeded").toString()))
                .isEqualByComparingTo("500");
    }

    @Test
    void corsAllowsAnyOriginAndApiMethods() {
        for (HttpMethod method : new HttpMethod[] {
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE
        }) {
            HttpHeaders headers = new HttpHeaders();
            headers.setOrigin("https://unlisted-frontend.example");
            headers.setAccessControlRequestMethod(method);
            headers.setAccessControlRequestHeaders(java.util.List.of("content-type"));
            ResponseEntity<Void> response = http.exchange(
                    "/api/transactions", HttpMethod.OPTIONS,
                    new org.springframework.http.HttpEntity<>(headers), Void.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
            assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(method);
        }
    }

    @Test
    void servesTheWebsiteFromTheSameServerAsTheApi() {
        ResponseEntity<String> page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Clareza | Controle financeiro");
        assertThat(page.getBody()).contains("script.js?v=7");
        assertThat(page.getHeaders().getCacheControl()).contains("no-store");

        ResponseEntity<String> script = http.getForEntity("/script.js?v=7", String.class);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getBody()).contains("window.location.origin");
    }

    private org.springframework.http.HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        return new org.springframework.http.HttpEntity<>(body);
    }
}
