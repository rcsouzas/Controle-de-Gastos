# Executar o Controle de Gastos

## Pré-requisitos

- Java 17 ou superior
- Maven 3.9 ou superior
- Navegador moderno e conexão à internet para carregar Chart.js e as fontes

## Aplicação (site e API na mesma porta)

Na raiz do projeto, inicie o servidor:

```bash
mvn -f backend/pom.xml spring-boot:run
```

Abra o próprio endereço do servidor, sem iniciar outro servidor para o frontend:

```bash
http://localhost:5500
```

O Spring Boot serve os arquivos do diretório `frontend` e a API pela mesma porta 5500. No GitHub Codespaces, abra o endereço encaminhado da porta 5500. O navegador envia as chamadas da API para a mesma origem que serviu o site, sem depender de uma segunda porta nem de CORS para o uso normal. O H2 cria o banco de dados em `backend/data/gastosdb`; os dados persistem entre reinicializações. A console do banco fica em `http://localhost:5500/h2-console`, usando JDBC URL `jdbc:h2:file:./data/gastosdb`, usuário `sa` e senha em branco.

## API

| Método | Endpoint | Descrição |
| --- | --- | --- |
| GET | `/api/transactions` | Lista transações |
| GET | `/api/transactions?year=2026&month=10&category=FOOD` | Filtra por ano, mês e categoria |
| GET | `/api/transactions/summary` | Retorna saldo total, receitas e despesas acumuladas |
| POST | `/api/transactions` | Cria transação |
| PUT | `/api/transactions/{id}` | Atualiza transação |
| DELETE | `/api/transactions/{id}` | Exclui transação |
| GET | `/api/recurrences` | Lista recorrências ativas |
| POST | `/api/recurrences` | Cria uma recorrência semanal ou mensal |
| PUT | `/api/recurrences/{id}` | Atualiza o valor e os dados da série |
| DELETE | `/api/recurrences/{id}` | Encerra a série sem apagar períodos registrados |
| POST | `/api/recurrences/{id}/occurrences` | Ajusta ou confirma uma ocorrência específica |
| GET | `/api/projection?through=2026-12-31` | Projeta o saldo até uma data |

Exemplo de payload:

```json
{
  "description": "Mercado da semana",
  "amount": 185.7,
  "date": "2026-10-26",
  "type": "EXPENSE",
  "category": "FOOD",
  "paymentMethod": "PIX",
  "planned": false
}
```

Defina `planned` como `true` para um lançamento futuro ainda não realizado. Ele não altera o saldo real, mas entra na projeção até a data escolhida. Omitir o campo equivale a `false`.

Recorrência é uma previsão semanal (`"frequency": "WEEKLY"`) ou mensal (`"frequency": "MONTHLY"`). Use `startDate`, `endDate` opcional, `type`, `category`, `paymentMethod`, `description` e `amount`. Uma ocorrência ajustada é criada em `/api/recurrences/{id}/occurrences` com o payload de transação acima e a data prevista. Se `planned` for `true`, ela substitui o valor previsto apenas para aquela data; se for `false`, passa a ser um lançamento confirmado. Ajustar o valor da própria recorrência altera as próximas previsões da série, sem reescrever períodos já registrados. Ao excluir uma recorrência, ela é encerrada e os lançamentos associados são preservados.

O resumo considera apenas transações confirmadas até hoje. A projeção parte desse saldo e soma os lançamentos futuros planejados e as ocorrências recorrentes até a data pedida. Respostas de valores `BigDecimal` são serializadas como números JSON:

```json
{
  "totalBalance": 1250.00,
  "totalIncome": 2000.00,
  "totalExpenses": 750.00
}
```

O retorno da projeção inclui `currentBalance`, `projectedBalance`, `lowestProjectedBalance`, `additionalBalanceNeeded`, `expectedIncome`, `expectedExpenses` e a lista `recurringOccurrences`. `additionalBalanceNeeded` informa quanto seria necessário acrescentar ao saldo atual para não ficar negativo em nenhum dia do período projetado.

Tipos aceitos: `INCOME`, `EXPENSE`. Categorias: `FOOD`, `HOUSING`, `LEISURE`, `TRANSPORT`, `INVESTMENTS`, `OTHER`, `SALARY`. Pagamentos: `PIX`, `CREDIT_CARD`, `DEBIT_CARD`, `CASH`, `BANK_TRANSFER`, `OTHER`.

## Testes

```bash
mvn -f backend/pom.xml test
```