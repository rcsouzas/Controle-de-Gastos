const API_BASE_URL = new URL("/", window.location.href).origin;
const API_URL = new URL("/api/transactions", API_BASE_URL).toString().replace(/\/$/, "");
const RECURRENCES_URL = new URL("/api/recurrences", API_BASE_URL).toString();
const PROJECTION_DATE_STORAGE_KEY = "clareza-projection-through";
const REPORT_START_MONTH_STORAGE_KEY = "clareza-report-start-month";
const REPORT_END_MONTH_STORAGE_KEY = "clareza-report-end-month";
const REPORT_INCLUDE_PLANNED_STORAGE_KEY = "clareza-report-include-planned";
const REPORT_CACHE_STORAGE_KEY = "clareza-report-cache";
const REPORT_ELEMENT_IDS = [
  "report-start-month", "report-end-month", "report-include-planned", "report-form", "update-report",
  "report-range-error", "report-period", "report-balance", "report-balance-label",
  "report-balance-card", "report-incomes", "report-expenses", "report-rows", "report-empty"
];

const categories = {
  FOOD: { label: "Alimentação", color: "#d4a747" },
  HOUSING: { label: "Moradia", color: "#5b8bb5" },
  LEISURE: { label: "Lazer", color: "#d8725e" },
  TRANSPORT: { label: "Transporte", color: "#157a62" },
  INVESTMENTS: { label: "Investimentos", color: "#8a7ba8" },
  OTHER: { label: "Outros", color: "#91a29a" },
  SALARY: { label: "Salário", color: "#157a62" },
  SUBSCRIPTION: { label: "Assinatura", color: "#96705b" },
  INSTALLMENT: { label: "Parcela", color: "#8d6f9f" },
  INVOICE: { label: "Fatura", color: "#bd6757" },
  REIMBURSEMENT: { label: "Reembolso", color: "#4c9b83" }
};

const expenseCategories = ["FOOD", "HOUSING", "LEISURE", "TRANSPORT", "INVESTMENTS", "OTHER", "SUBSCRIPTION", "INSTALLMENT", "INVOICE"];
const incomeCategories = ["SALARY", "INVESTMENTS", "OTHER", "REIMBURSEMENT"];

const paymentMethods = {
  PIX: "Pix",
  CREDIT_CARD: "Cartão de crédito",
  DEBIT_CARD: "Cartão de débito",
  CASH: "Dinheiro",
  BANK_TRANSFER: "Transferência",
  OTHER: "Outro"
};

const state = {
  transactions: [], summary: null, projection: null, recurrences: [],
  flowChart: null, categoryChart: null, toastTimer: null, filtersInitialized: false, reportRange: null
};
const elements = Object.fromEntries([
  "today-label", "theme-toggle", "theme-icon", "summary-month", "balance-total", "income-total", "expense-total",
  "donut-total", "category-legend", "transactions-body", "transaction-count", "empty-state", "filter-month",
  "filter-year", "filter-category", "clear-filters", "connection-status", "entry-panel", "panel-backdrop",
  "transaction-form", "transaction-id", "form-title", "description", "amount", "date", "category",
  "payment-method", "submit-button", "close-form", "cancel-form", "new-transaction-button", "toast",
  "recurrence-id", "source-recurring-id", "recurrence-options", "frequency", "end-date", "planned",
  "planned-option", "installment-options", "installment-count", "projection-through", "projected-balance", "projection-current-balance",
  "projected-income", "projected-expenses", "lowest-projected-balance", "additional-balance-needed",
  "recurrences-list", "occurrences-list", "new-recurrence-button", "report-start-month", "report-end-month",
  "report-range-error", "report-period", "report-balance", "report-balance-label", "report-incomes",
  "report-expenses", "report-rows", "report-empty", "report-balance-card", "report-form", "update-report",
  "report-include-planned"
].map((id) => [id, document.getElementById(id)]));

const money = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const dateFormat = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", year: "numeric" });
const monthFormat = new Intl.DateTimeFormat("pt-BR", { month: "long" });
const currentDate = new Date();

function setStatus(message = "") {
  elements["connection-status"].textContent = message;
  elements["connection-status"].hidden = !message;
}

async function request(url = API_URL, options = {}) {
  const headers = { ...options.headers };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(url, { ...options, headers });
  } catch (error) {
    console.error("Falha de conexão com a API", options.method ?? "GET", url, error);
    throw new Error(`Não foi possível acessar ${url}. Confirme que o site está aberto pela porta 5500 e tente novamente.`);
  }
  if (!response.ok) {
    let message = "";
    try {
      const body = await response.text();
      try {
        const parsed = JSON.parse(body);
        message = parsed.message ?? body;
      } catch {
        message = body;
      }
    } catch (error) {
      console.error("Não foi possível ler a resposta de erro da API", error);
    }
    message = message.trim() || response.statusText || "Erro desconhecido";
    console.error("API retornou erro", options.method ?? "GET", url, response.status, message);
    throw new Error(`Erro ${response.status}: ${message}`);
  }
  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch (error) {
    console.error("API retornou uma resposta JSON vazia ou inválida", options.method ?? "GET", url, error);
    throw new Error("A API retornou uma resposta vazia ou inválida.");
  }
}

async function loadTransactions(refreshReport = true) {
  try {
    const through = elements["projection-through"].value;
    const [transactions, summary, recurrences, projection] = await Promise.all([
      request(),
      request(`${API_URL}/summary`),
      request(RECURRENCES_URL),
      request(`${API_BASE_URL}/api/projection?through=${encodeURIComponent(through)}`)
    ]);
    if (projection.through !== through) {
      elements["projection-through"].value = projection.through;
      storageSet(PROJECTION_DATE_STORAGE_KEY, projection.through);
    }
    state.transactions = transactions;
    state.summary = summary;
    state.recurrences = recurrences;
    state.projection = projection;
    setFilterOptions();
    setStatus("");
    render();
    if (refreshReport) void generateReport();
    return true;
  } catch (error) {
    setStatus(`Não foi possível conectar à API (${API_URL}). Verifique se o servidor do site está iniciado na porta 5500. ${error.message}`);
    render();
    return false;
  }
}

function transactionsForSelectedPeriod() {
  const month = elements["filter-month"].value;
  const year = elements["filter-year"].value;
  const category = elements["filter-category"].value;
  return state.transactions.filter((transaction) => {
    const [transactionYear, transactionMonth] = transaction.date.split("-");
    return (!month || transactionMonth === month)
      && (!year || transactionYear === year)
      && (!category || transaction.category === category);
  }).sort((left, right) => right.date.localeCompare(left.date) || right.id - left.id);
}

function currentMonthTransactions() {
  const month = String(currentDate.getMonth() + 1).padStart(2, "0");
  const year = String(currentDate.getFullYear());
  return state.transactions.filter((item) => !item.planned && item.date <= toISODate(currentDate)
    && item.date.startsWith(`${year}-${month}`));
}

function sumByType(transactions, type) {
  return transactions.reduce((total, item) => item.type === type ? total + Number(item.amount) : total, 0);
}

function renderSummary() {
  const summary = state.summary ?? { totalBalance: 0, totalIncome: 0, totalExpenses: 0 };
  elements["summary-month"].textContent = "Acumulado";
  elements["balance-total"].textContent = money.format(Number(summary.totalBalance));
  elements["income-total"].textContent = money.format(Number(summary.totalIncome));
  elements["expense-total"].textContent = money.format(Number(summary.totalExpenses));
  const monthlyExpenses = sumByType(currentMonthTransactions(), "EXPENSE");
  elements["donut-total"].textContent = money.format(monthlyExpenses).replace(",00", "");
}

function renderTransactions() {
  const transactions = transactionsForSelectedPeriod();
  elements["transaction-count"].textContent = `${transactions.length} ${transactions.length === 1 ? "registro" : "registros"}`;
  elements["transactions-body"].replaceChildren(...transactions.map((transaction) => createTransactionRow(transaction)));
  elements["empty-state"].hidden = transactions.length > 0;
}

function renderReport(report) {
  if (!isValidReportResponse(report)) {
    throw new Error("A API retornou um relatório vazio ou inválido.");
  }
  const reportElements = getReportElements();
  if (!reportElements) return;
  const balance = Number(report.totalBalance);
  reportElements["report-period"].textContent = `${formatMonth(report.startMonth)} a ${formatMonth(report.endMonth)}`;
  reportElements["report-balance"].textContent = money.format(balance);
  reportElements["report-balance-label"].textContent = balance > 0 ? "Saldo positivo" : balance < 0 ? "Saldo negativo" : "Saldo equilibrado";
  reportElements["report-balance-card"].classList.toggle("positive", balance > 0);
  reportElements["report-balance-card"].classList.toggle("negative", balance < 0);
  reportElements["report-incomes"].textContent = money.format(Number(report.totalIncome));
  reportElements["report-expenses"].textContent = money.format(Number(report.totalExpenses));

  const rows = report.monthlyTotals.map((totals) => {
    const row = document.createElement("tr");
    const monthBalance = Number(totals.totalBalance);
    const values = [
      formatMonth(totals.month),
      money.format(Number(totals.totalIncome)),
      money.format(Number(totals.totalExpenses)),
      money.format(monthBalance)
    ];
    values.forEach((value, index) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      if (index > 0) cell.classList.add("amount-cell");
      if (index === 1 || (index === 3 && monthBalance >= 0)) cell.classList.add("income");
      if (index === 2 || (index === 3 && monthBalance < 0)) cell.classList.add("expense");
      row.append(cell);
    });
    return row;
  });
  reportElements["report-rows"].replaceChildren(...rows);
  reportElements["report-empty"].hidden = rows.length > 0;
}

function formatMonth(value) {
  const [year, month] = value.split("-").map(Number);
  return new Intl.DateTimeFormat("pt-BR", { month: "long", year: "numeric" }).format(new Date(year, month - 1, 1));
}

function isValidReportMonth(value) {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(value);
}

function isValidReportResponse(report) {
  const validAmount = (value) => value !== null && value !== "" && Number.isFinite(Number(value));
  return report !== null
    && typeof report === "object"
    && isValidReportMonth(report.startMonth)
    && isValidReportMonth(report.endMonth)
    && validAmount(report.totalBalance)
    && validAmount(report.totalIncome)
    && validAmount(report.totalExpenses)
    && Array.isArray(report.monthlyTotals)
    && report.monthlyTotals.every((month) => month
      && isValidReportMonth(month.month)
      && validAmount(month.totalBalance)
      && validAmount(month.totalIncome)
      && validAmount(month.totalExpenses));
}

function savedReportRange() {
  const startMonth = storageGet(REPORT_START_MONTH_STORAGE_KEY);
  const endMonth = storageGet(REPORT_END_MONTH_STORAGE_KEY);
  if (isValidReportMonth(startMonth) && isValidReportMonth(endMonth) && startMonth <= endMonth) {
    return { startMonth, endMonth };
  }
  return null;
}

function storageGet(key) {
  try {
    return window.localStorage.getItem(key);
  } catch (error) {
    console.error(`Não foi possível ler o estado salvo (${key}).`, error);
    return null;
  }
}

function storageSet(key, value) {
  try {
    window.localStorage.setItem(key, value);
    return true;
  } catch (error) {
    console.error(`Não foi possível salvar o estado (${key}).`, error);
    return false;
  }
}

function getReportElements() {
  const missingElements = REPORT_ELEMENT_IDS.filter((id) => !elements[id]);
  if (missingElements.length) {
    console.error("Relatório não inicializado: faltam elementos no HTML.", missingElements);
    return null;
  }
  return elements;
}

function savedReportCache() {
  try {
    const cached = JSON.parse(storageGet(REPORT_CACHE_STORAGE_KEY) ?? "null");
    if (!cached || typeof cached !== "object"
        || typeof cached.incluirPlanejados !== "boolean"
        || !isValidReportResponse(cached.report)) {
      return null;
    }
    return cached;
  } catch (error) {
    console.error("O relatório salvo no navegador está inválido.", error);
    return null;
  }
}

async function generateReport() {
  const reportElements = getReportElements();
  if (!reportElements) return;
  const startMonth = reportElements["report-start-month"].value;
  const endMonth = reportElements["report-end-month"].value;
  const incluirPlanejados = reportElements["report-include-planned"].checked;
  if (!isValidReportMonth(startMonth) || !isValidReportMonth(endMonth) || startMonth > endMonth) {
    reportElements["report-range-error"].textContent = "Informe um período válido: o mês inicial deve ser anterior ou igual ao mês final.";
    reportElements["report-range-error"].hidden = false;
    return;
  }

  storageSet(REPORT_START_MONTH_STORAGE_KEY, startMonth);
  storageSet(REPORT_END_MONTH_STORAGE_KEY, endMonth);
  storageSet(REPORT_INCLUDE_PLANNED_STORAGE_KEY, String(incluirPlanejados));

  const button = reportElements["update-report"];
  button.disabled = true;
  button.textContent = "Gerando...";
  reportElements["report-range-error"].hidden = true;
  try {
    const params = new URLSearchParams({
      startMonth,
      endMonth,
      incluirPlanejados: String(incluirPlanejados)
    });
    const report = await request(`${API_URL}/report?${params.toString()}`);
    if (!isValidReportResponse(report)) {
      throw new Error("A API retornou um relatório vazio ou inválido.");
    }
    if (report.startMonth !== startMonth || report.endMonth !== endMonth) {
      throw new Error("A API retornou um relatório para um período diferente do selecionado.");
    }
    state.reportRange = { startMonth, endMonth };
    renderReport(report);
    storageSet(REPORT_CACHE_STORAGE_KEY, JSON.stringify({ report, incluirPlanejados }));
  } catch (error) {
    console.error("Falha ao gerar relatório mensal", error);
    reportElements["report-range-error"].textContent = `Não foi possível gerar o relatório. ${error.message}`;
    reportElements["report-range-error"].hidden = false;
  } finally {
    button.disabled = false;
    button.textContent = "Gerar relatório";
  }
}

function createTransactionRow(transaction) {
  const category = categories[transaction.category] ?? categories.OTHER;
  const row = document.createElement("tr");
  row.innerHTML = `
    <td>${escapeHtml(transaction.description)}${transaction.planned ? '<span class="planned-badge">Planejado</span>' : ""}</td>
    <td><span class="category-badge" style="--badge-color:${category.color}">${category.label}</span></td>
    <td>${dateFormat.format(new Date(`${transaction.date}T12:00:00`))}</td>
    <td>${paymentMethods[transaction.paymentMethod] ?? "Outro"}</td>
    <td class="amount-cell ${transaction.type === "INCOME" ? "income" : "expense"}">${transaction.type === "INCOME" ? "+ " : "− "}${money.format(transaction.amount)}</td>
    <td><div class="row-actions"><button class="row-action" type="button" data-action="edit" aria-label="Editar ${escapeHtml(transaction.description)}" title="Editar">✎</button><button class="row-action" type="button" data-action="delete" aria-label="Excluir ${escapeHtml(transaction.description)}" title="Excluir">×</button></div></td>`;
  row.querySelector('[data-action="edit"]').addEventListener("click", () => openForm(transaction));
  row.querySelector('[data-action="delete"]').addEventListener("click", () => deleteTransaction(transaction));
  return row;
}

function renderPlanning() {
  const projection = state.projection;
  if (projection) {
    elements["projected-balance"].textContent = money.format(Number(projection.projectedBalance));
    elements["projection-current-balance"].textContent = money.format(Number(projection.currentBalance));
    elements["projected-income"].textContent = money.format(Number(projection.expectedIncome));
    elements["projected-expenses"].textContent = money.format(Number(projection.expectedExpenses));
    elements["lowest-projected-balance"].textContent = money.format(Number(projection.lowestProjectedBalance));
    elements["additional-balance-needed"].textContent = money.format(Number(projection.additionalBalanceNeeded));
  }
  renderRecurrences();
  renderOccurrences();
}

function renderRecurrences() {
  const list = elements["recurrences-list"];
  list.replaceChildren();
  if (!state.recurrences.length) {
    const empty = document.createElement("p");
    empty.className = "list-empty";
    empty.textContent = "Nenhuma recorrência cadastrada.";
    list.append(empty);
    return;
  }
  state.recurrences.forEach((recurrence) => {
    const item = document.createElement("div");
    item.className = "recurrence-item";
    const frequency = recurrence.frequency === "WEEKLY" ? "Toda semana" : "Todo mês";
    const type = recurrence.type === "INCOME" ? "Receita" : "Despesa";
    item.innerHTML = `<div class="recurrence-description"><strong>${escapeHtml(recurrence.description)}</strong><span>${type} · ${frequency} · desde ${dateFormat.format(new Date(`${recurrence.startDate}T12:00:00`))}</span></div>
      <strong class="recurrence-amount">${money.format(Number(recurrence.amount))}</strong>
      <div class="recurrence-actions"><button type="button" class="row-action" data-action="edit" aria-label="Editar ${escapeHtml(recurrence.description)}">✎</button><button type="button" class="row-action" data-action="delete" aria-label="Excluir ${escapeHtml(recurrence.description)}">×</button></div>`;
    item.querySelector('[data-action="edit"]').addEventListener("click", () => openRecurrenceForm(recurrence));
    item.querySelector('[data-action="delete"]').addEventListener("click", () => deleteRecurrence(recurrence));
    list.append(item);
  });
}

function renderOccurrences() {
  const list = elements["occurrences-list"];
  list.replaceChildren();
  const occurrences = state.projection?.recurringOccurrences ?? [];
  if (!occurrences.length) {
    const empty = document.createElement("p");
    empty.className = "list-empty";
    empty.textContent = "Nenhuma ocorrência recorrente até a data selecionada.";
    list.append(empty);
    return;
  }
  occurrences.forEach((occurrence) => {
    const item = document.createElement("div");
    item.className = "occurrence-item";
    const type = occurrence.type === "INCOME" ? "Receita" : "Despesa";
    item.innerHTML = `<div><strong>${escapeHtml(occurrence.description)}</strong><span>${type} · ${dateFormat.format(new Date(`${occurrence.date}T12:00:00`))}</span></div>
      <strong class="${occurrence.type === "INCOME" ? "income" : "expense"}">${money.format(Number(occurrence.amount))}</strong>
      <button type="button" class="button button-text">Ajustar período</button>`;
    item.querySelector("button").addEventListener("click", () => openOccurrenceForm(occurrence));
    list.append(item);
  });
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  })[character]);
}

function renderCharts() {
  renderFlowChart();
  renderCategoryChart();
}

function renderFlowChart() {
  const canvas = document.getElementById("flow-chart");
  if (!window.Chart || !canvas) return;
  const months = [];
  for (let offset = 5; offset >= 0; offset -= 1) {
    months.push(new Date(currentDate.getFullYear(), currentDate.getMonth() - offset, 1));
  }
  const labels = months.map((month) => month.toLocaleDateString("pt-BR", { month: "short" }).replace(".", ""));
  const values = (type) => months.map((month) => {
    const yearMonth = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, "0")}`;
    return sumByType(state.transactions.filter((item) => !item.planned
      && item.date <= toISODate(currentDate)
      && item.date.startsWith(yearMonth)), type);
  });
  state.flowChart?.destroy();
  state.flowChart = new Chart(canvas, {
    type: "bar",
    data: { labels, datasets: [
      { label: "Receitas", data: values("INCOME"), backgroundColor: "#157a62", borderRadius: 3, maxBarThickness: 19 },
      { label: "Despesas", data: values("EXPENSE"), backgroundColor: "#d8725e", borderRadius: 3, maxBarThickness: 19 }
    ] },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: (context) => `${context.dataset.label}: ${money.format(context.raw)}` } } },
      scales: {
        x: { grid: { display: false }, border: { display: false }, ticks: { color: chartTextColor(), font: { family: "DM Sans", size: 10 } } },
        y: { beginAtZero: true, grid: { color: chartGridColor() }, border: { display: false }, ticks: { color: chartTextColor(), maxTicksLimit: 4, callback: (value) => money.format(value).replace(",00", "") } }
      }
    }
  });
}

function renderCategoryChart() {
  const canvas = document.getElementById("category-chart");
  if (!window.Chart || !canvas || !elements["category-legend"]) return;
  const expenses = currentMonthTransactions().filter((item) => item.type === "EXPENSE");
  const amounts = Object.keys(categories).filter((key) => key !== "SALARY").map((key) => ({
    key,
    value: expenses.filter((item) => item.category === key).reduce((total, item) => total + Number(item.amount), 0)
  }));
  const visible = amounts.filter((item) => item.value > 0);
  const chartItems = visible.length ? visible : [{ key: "OTHER", value: 1 }];
  elements["category-legend"].replaceChildren(...visible.map((item) => {
    const legend = document.createElement("div");
    legend.className = "category-legend-item";
    legend.innerHTML = `<i style="background:${categories[item.key].color}"></i><span>${categories[item.key].label}</span><strong>${money.format(item.value)}</strong>`;
    return legend;
  }));
  if (!visible.length) {
    const emptyLabel = document.createElement("span");
    emptyLabel.className = "category-legend-item";
    emptyLabel.textContent = "Sem despesas neste mês";
    elements["category-legend"].append(emptyLabel);
  }
  state.categoryChart?.destroy();
  state.categoryChart = new Chart(canvas, {
    type: "doughnut",
    data: {
      labels: chartItems.map((item) => categories[item.key].label),
      datasets: [{ data: chartItems.map((item) => item.value), backgroundColor: chartItems.map((item) => categories[item.key].color), borderWidth: 0, hoverOffset: 4 }]
    },
    options: { responsive: true, maintainAspectRatio: false, cutout: "76%", plugins: { legend: { display: false }, tooltip: { enabled: visible.length > 0, callbacks: { label: (context) => money.format(context.raw) } } } }
  });
}

function chartTextColor() {
  return document.documentElement.dataset.theme === "dark" ? "#a1afa7" : "#87938c";
}

function chartGridColor() {
  return document.documentElement.dataset.theme === "dark" ? "#2c3933" : "#edf0ed";
}

function render() {
  renderSummary();
  renderTransactions();
  renderPlanning();
  renderCharts();
}

function setFormOptions() {
  document.querySelectorAll('input[name="type"]').forEach((input) => {
    input.addEventListener("change", updateFormOptions);
  });
  elements.category.addEventListener("change", updateInstallmentOptions);
  updateFormOptions();
}

function updateFormOptions(preferredCategory = elements.category.value, preferredPaymentMethod = elements["payment-method"].value) {
  const type = document.querySelector('input[name="type"]:checked').value;
  const allowedCategories = type === "EXPENSE" ? expenseCategories : incomeCategories;
  const availableCategories = Object.entries(categories).filter(([value]) => allowedCategories.includes(value));
  elements.category.innerHTML = availableCategories
    .map(([value, category]) => `<option value="${value}">${category.label}</option>`).join("");
  elements.category.value = availableCategories.some(([value]) => value === preferredCategory)
    ? preferredCategory
    : type === "INCOME" ? "SALARY" : "FOOD";

  const availablePaymentMethods = Object.entries(paymentMethods).filter(([value]) => type === "EXPENSE"
    || ["PIX", "BANK_TRANSFER", "CASH", "OTHER"].includes(value));
  elements["payment-method"].innerHTML = availablePaymentMethods
    .map(([value, label]) => `<option value="${value}">${label}</option>`).join("");
  elements["payment-method"].value = availablePaymentMethods.some(([value]) => value === preferredPaymentMethod)
    ? preferredPaymentMethod
    : "PIX";
  updateInstallmentOptions();
}

function updateInstallmentOptions() {
  const isNewInstallment = elements.category.value === "INSTALLMENT"
    && !elements["transaction-id"].value
    && !elements["recurrence-id"].value
    && !elements["source-recurring-id"].value
    && elements["recurrence-options"].hidden;
  elements["installment-options"].hidden = !isNewInstallment;
}

function setFilterOptions() {
  const selectedMonth = elements["filter-month"].value;
  const selectedYear = elements["filter-year"].value;
  const selectedCategory = elements["filter-category"].value;
  const monthLabels = ["Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"];
  elements["filter-month"].innerHTML = '<option value="">Todos os meses</option>' + monthLabels.map((label, index) => `<option value="${String(index + 1).padStart(2, "0")}">${label}</option>`).join("");
  const years = new Set(state.transactions.map((item) => Number(item.date.slice(0, 4))));
  for (let year = currentDate.getFullYear(); year <= currentDate.getFullYear() + 10; year++) {
    years.add(year);
  }
  elements["filter-year"].innerHTML = '<option value="">Todos os anos</option>' + [...years].sort((a, b) => b - a).map((year) => `<option value="${year}">${year}</option>`).join("");
  elements["filter-category"].innerHTML = '<option value="">Todas as categorias</option>' + Object.entries(categories)
    .map(([value, category]) => `<option value="${value}">${category.label}</option>`).join("");
  if (!state.filtersInitialized) {
    elements["filter-month"].value = String(currentDate.getMonth() + 1).padStart(2, "0");
    elements["filter-year"].value = String(currentDate.getFullYear());
    state.filtersInitialized = true;
  } else {
    elements["filter-month"].value = selectedMonth;
    elements["filter-year"].value = selectedYear && years.has(Number(selectedYear)) ? selectedYear : "";
    elements["filter-category"].value = selectedCategory;
  }
}

function openForm(transaction = null, recurrence = null, occurrence = null, recurrenceMode = false) {
  elements["transaction-form"].reset();
  elements["transaction-id"].value = transaction?.id ?? "";
  elements["recurrence-id"].value = recurrence?.id ?? "";
  elements["source-recurring-id"].value = transaction?.recurringTransactionId ?? occurrence?.recurringTransactionId ?? "";
  const editingRecurrence = recurrenceMode || Boolean(recurrence);
  const isOccurrence = Boolean(occurrence);
  const source = transaction ?? recurrence ?? occurrence;
  elements["form-title"].textContent = editingRecurrence
    ? (recurrence?.id ? "Editar recorrência" : "Nova recorrência")
    : isOccurrence ? "Ajustar ocorrência" : transaction ? "Editar transação" : "Nova transação";
  elements["submit-button"].textContent = editingRecurrence
    ? "Salvar recorrência"
    : transaction ? "Salvar alterações" : isOccurrence ? "Salvar período" : "Salvar transação";
  elements.description.value = source?.description ?? "";
  elements.amount.value = source?.amount ?? "";
  elements.date.value = transaction?.date ?? recurrence?.startDate ?? occurrence?.date ?? toISODate(currentDate);
  elements.date.disabled = isOccurrence || Boolean(transaction?.recurrenceDate);
  document.querySelector(`input[name="type"][value="${source?.type ?? "EXPENSE"}"]`).checked = true;
  updateFormOptions(source?.category ?? (source?.type === "INCOME" ? "SALARY" : "FOOD"), source?.paymentMethod ?? "PIX");
  elements["frequency"].value = recurrence?.frequency ?? "WEEKLY";
  elements["end-date"].value = recurrence?.endDate ?? "";
  elements["recurrence-options"].hidden = !editingRecurrence;
  elements["planned-option"].hidden = editingRecurrence;
  elements.planned.checked = transaction?.planned ?? isOccurrence;
  elements["installment-count"].value = "2";
  updateInstallmentOptions();
  elements["entry-panel"].classList.add("open");
  elements["entry-panel"].setAttribute("aria-hidden", "false");
  elements["panel-backdrop"].classList.add("open");
  elements.description.focus();
}

function openRecurrenceForm(recurrence = null) {
  openForm(null, recurrence, null, true);
}

function openOccurrenceForm(occurrence) {
  openForm(null, null, occurrence);
}

function closeForm() {
  elements["entry-panel"].classList.remove("open");
  elements["entry-panel"].setAttribute("aria-hidden", "true");
  elements["panel-backdrop"].classList.remove("open");
}

async function saveTransaction(event) {
  event.preventDefault();
  const id = elements["transaction-id"].value;
  const recurrenceId = elements["recurrence-id"].value;
  const sourceRecurringId = elements["source-recurring-id"].value;
  const transaction = {
    description: elements.description.value.trim(),
    amount: Number(elements.amount.value),
    date: elements.date.value,
    type: document.querySelector('input[name="type"]:checked').value,
    category: elements.category.value,
    paymentMethod: elements["payment-method"].value,
    planned: elements.planned.checked
  };
  try {
    let successMessage;
    if (recurrenceId || elements["recurrence-options"].hidden === false) {
      const recurrence = {
        ...transaction,
        startDate: transaction.date,
        endDate: elements["end-date"].value || null,
        frequency: elements.frequency.value
      };
      delete recurrence.date;
      delete recurrence.planned;
      await request(recurrenceId ? `${RECURRENCES_URL}/${recurrenceId}` : RECURRENCES_URL, {
        method: recurrenceId ? "PUT" : "POST",
        body: JSON.stringify(recurrence)
      });
      successMessage = recurrenceId ? "Recorrência atualizada." : "Recorrência adicionada.";
    } else {
      if (!id && !sourceRecurringId && transaction.category === "INSTALLMENT") {
        await request(`${API_URL}/installments`, {
          method: "POST",
          body: JSON.stringify({
            transaction,
            count: Number(elements["installment-count"].value)
          })
        });
        successMessage = "Parcelas adicionadas.";
      } else {
        const url = id
          ? `${API_URL}/${id}`
          : sourceRecurringId
            ? `${RECURRENCES_URL}/${sourceRecurringId}/occurrences`
            : API_URL;
        const method = id ? "PUT" : "POST";
        await request(url, { method, body: JSON.stringify(transaction) });
        successMessage = id ? "Transação atualizada." : sourceRecurringId ? "Período registrado." : "Transação adicionada.";
      }
    }
    closeForm();
    const refreshed = await loadTransactions();
    showToast(refreshed ? successMessage : `${successMessage} Não foi possível atualizar a tela.`);
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteRecurrence(recurrence) {
  if (!window.confirm(`Encerrar a recorrência "${recurrence.description}"? Os períodos já registrados serão mantidos.`)) return;
  try {
    await request(`${RECURRENCES_URL}/${recurrence.id}`, { method: "DELETE" });
    const refreshed = await loadTransactions();
    showToast(refreshed ? "Recorrência encerrada." : "Recorrência encerrada, mas a tela não foi atualizada.");
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteTransaction(transaction) {
  const confirmation = transaction.installmentGroupId
    ? `Excluir "${transaction.description}" e todas as parcelas dessa compra?`
    : `Excluir a transação "${transaction.description}"?`;
  if (!window.confirm(confirmation)) return;
  try {
    await request(`${API_URL}/${transaction.id}`, { method: "DELETE" });
    const refreshed = await loadTransactions();
    showToast(refreshed
      ? "Transação excluída."
      : "Transação excluída, mas não foi possível atualizar a lista.");
  } catch (error) {
    showToast(error.message);
  }
}

function showToast(message) {
  elements.toast.textContent = message;
  elements.toast.classList.add("visible");
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => elements.toast.classList.remove("visible"), 3200);
}

function setTheme(theme) {
  document.documentElement.dataset.theme = theme;
  storageSet("clareza-theme", theme);
  const dark = theme === "dark";
  elements["theme-toggle"].setAttribute("aria-label", dark ? "Ativar modo claro" : "Ativar modo escuro");
  elements["theme-icon"].textContent = dark ? "☼" : "◐";
  if (state.transactions.length) renderCharts();
}

function toISODate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function savedProjectionDate() {
  const savedDate = storageGet(PROJECTION_DATE_STORAGE_KEY);
  if (!savedDate || !/^\d{4}-\d{2}-\d{2}$/.test(savedDate)) return null;
  const parsedDate = new Date(`${savedDate}T00:00:00`);
  if (Number.isNaN(parsedDate.getTime()) || toISODate(parsedDate) !== savedDate) return null;
  return savedDate >= toISODate(currentDate) ? savedDate : null;
}

async function loadProjection() {
  try {
    const through = elements["projection-through"].value;
    if (through) storageSet(PROJECTION_DATE_STORAGE_KEY, through);
    state.projection = await request(`${API_BASE_URL}/api/projection?through=${encodeURIComponent(through)}`);
    if (state.projection.through !== through) {
      elements["projection-through"].value = state.projection.through;
      storageSet(PROJECTION_DATE_STORAGE_KEY, state.projection.through);
    }
    renderPlanning();
  } catch (error) {
    showToast(error.message);
  }
}

function initialize() {
  elements["today-label"].textContent = new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium" }).format(currentDate);
  const defaultThrough = new Date(currentDate.getFullYear(), currentDate.getMonth() + 3, currentDate.getDate());
  elements["projection-through"].value = savedProjectionDate() ?? toISODate(defaultThrough);
  const currentMonth = toISODate(currentDate).slice(0, 7);
  state.reportRange = savedReportRange() ?? { startMonth: currentMonth, endMonth: currentMonth };
  if (elements["report-start-month"] && elements["report-end-month"] && elements["report-include-planned"]) {
    elements["report-start-month"].value = state.reportRange.startMonth;
    elements["report-end-month"].value = state.reportRange.endMonth;
    elements["report-include-planned"].checked =
      storageGet(REPORT_INCLUDE_PLANNED_STORAGE_KEY) === "true";
  }
  elements["projection-through"].addEventListener("change", loadProjection);
  if (getReportElements()) {
    const cachedReport = savedReportCache();
    if (cachedReport
        && cachedReport.report.startMonth === state.reportRange.startMonth
        && cachedReport.report.endMonth === state.reportRange.endMonth
        && cachedReport.incluirPlanejados === elements["report-include-planned"].checked) {
      renderReport(cachedReport.report);
    }
    elements["update-report"].addEventListener("click", generateReport);
    elements["report-form"].addEventListener("submit", (event) => {
      event.preventDefault();
      void generateReport();
    });
  }
  elements["filter-month"].addEventListener("change", renderTransactions);
  elements["filter-year"].addEventListener("change", renderTransactions);
  elements["filter-category"].addEventListener("change", renderTransactions);
  elements["clear-filters"].addEventListener("click", () => {
    elements["filter-month"].value = "";
    elements["filter-year"].value = "";
    elements["filter-category"].value = "";
    renderTransactions();
  });
  elements["new-transaction-button"].addEventListener("click", () => openForm());
  elements["new-recurrence-button"].addEventListener("click", () => openRecurrenceForm());
  elements.date.addEventListener("change", () => {
    elements.planned.checked = elements.date.value > toISODate(currentDate);
  });
  elements["close-form"].addEventListener("click", closeForm);
  elements["cancel-form"].addEventListener("click", closeForm);
  elements["panel-backdrop"].addEventListener("click", closeForm);
  elements["transaction-form"].addEventListener("submit", saveTransaction);
  elements["theme-toggle"].addEventListener("click", () => setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));
  const updateNavigation = () => {
    const activeHash = window.location.hash || "#inicio";
    document.querySelectorAll(".nav-item").forEach((item) => {
      item.classList.toggle("active", item.getAttribute("href") === activeHash);
    });
  };
  window.addEventListener("hashchange", updateNavigation);
  updateNavigation();
  document.addEventListener("keydown", (event) => { if (event.key === "Escape") closeForm(); });
  setFormOptions();
  setFilterOptions();
  setTheme(storageGet("clareza-theme") ?? "light");
  void generateReport();
  loadTransactions(false);
}

initialize();