# Executar o Controle de Gastos

## Pré-requisitos

- Java 17 ou superior
- Maven 3.9 ou superior
- Navegador moderno e conexão à internet para carregar Chart.js e as fontes

## Iniciar o servidor

1. Abra um terminal na raiz do projeto (a pasta que contém `backend/` e `docs/`).
2. Confira se Java e Maven estão instalados:

```bash
java -version
mvn -version
```

3. Inicie o Spring Boot:

```bash
mvn -f backend/pom.xml spring-boot:run
```

Mantenha esse terminal aberto enquanto usar o sistema. Aguarde a mensagem indicando que o Tomcat iniciou na porta `5500`; na primeira execução, o Maven pode levar alguns minutos para baixar as dependências.

## Abrir o site e a API

Com o servidor iniciado, abra no navegador:

```text
http://localhost:5500
```

O Spring Boot serve os arquivos do diretório `frontend` e a API pela mesma porta. Não abra o `index.html` diretamente nem inicie outro servidor para o frontend.

No GitHub Codespaces, abra a aba **Ports**, localize a porta `5500` e clique em **Open in Browser**. Se necessário, altere a visibilidade da porta conforme as permissões do seu Codespace.

Para parar o servidor, volte ao terminal em que ele está rodando e pressione `Ctrl+C`. Para abrir o sistema novamente depois, execute o comando de inicialização outra vez.

Se a porta `5500` já estiver ocupada, encerre a execução anterior do servidor e tente iniciar novamente. Se o site não atualizar após uma alteração, recarregue a página com `Ctrl+F5`.

## Persistência dos dados e atualizações

O banco H2 fica fora do repositório, no diretório pessoal de cada usuário: `~/.controle-de-gastos/data/gastosdb.mv.db` (por exemplo, `/home/usuario/.controle-de-gastos/data/gastosdb.mv.db`). Assim, commits, pulls e atualizações dos arquivos do projeto não incluem nem substituem despesas, receitas, parcelas ou assinaturas.

Na primeira inicialização desta versão, se ainda não existir banco nesse novo local, a aplicação copia o banco legado de `backend/data/gastosdb.mv.db` (ou `backend/data/controle-gastos.mv.db`) sem apagar o original. Se o banco externo já existir, ele nunca é sobrescrito pela cópia. A atualização de schema usa `ddl-auto=update`, preservando os registros.

Para manter uma cópia de segurança, pare o servidor com `Ctrl+C` antes de copiar o arquivo `.mv.db`. Guarde o backup fora do repositório. Não adicione o banco ao Git nem use opções do Hibernate que recriem o schema, como `create` ou `create-drop`.

A console do banco fica em `http://localhost:5500/h2-console`, usando JDBC URL `jdbc:h2:file:${user.home}/.controle-de-gastos/data/gastosdb`, usuário `sa` e senha em branco.

## API

| Método | Endpoint | Descrição |
| --- | --- | --- |
| GET | `/api/transactions` | Lista transações |
| GET | `/api/transactions?year=2026&month=10&category=FOOD` | Filtra por ano, mês e categoria |
| GET | `/api/transactions/summary` | Retorna saldo total, receitas e despesas acumuladas |
| POST | `/api/transactions` | Cria transação |
| POST | `/api/transactions/installments` | Cria parcelas mensais com o mesmo valor |
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

Para criar parcelas, envie `POST /api/transactions/installments` com a transação dentro de `transaction` e a quantidade total em `count` (de 1 a 120). A primeira parcela usa a data informada; as demais vencem mensalmente no mesmo dia, ajustado para o último dia do mês quando necessário. Parcelas futuras são salvas como planejadas e entram na projeção sem alterar o saldo real:

```json
{
  "transaction": {
    "description": "Compra parcelada",
    "amount": 150,
    "date": "2026-10-26",
    "type": "EXPENSE",
    "category": "INSTALLMENT",
    "paymentMethod": "CREDIT_CARD",
    "planned": false
  },
  "count": 6
}
```

Recorrência é uma previsão semanal (`"frequency": "WEEKLY"`) ou mensal (`"frequency": "MONTHLY"`). Use `startDate`, `endDate` opcional, `type`, `category`, `paymentMethod`, `description` e `amount`. Uma ocorrência ajustada é criada em `/api/recurrences/{id}/occurrences` com o payload de transação acima e a data prevista. Se `planned` for `true`, ela substitui o valor previsto apenas para aquela data; se for `false`, passa a ser um lançamento confirmado. Ajustar o valor da própria recorrência altera as próximas previsões da série, sem reescrever períodos já registrados. Ao excluir uma recorrência, ela é encerrada e os lançamentos associados são preservados.

Categorias de despesa: `FOOD`, `HOUSING`, `LEISURE`, `TRANSPORT`, `INVESTMENTS`, `OTHER`, `SUBSCRIPTION` e `INSTALLMENT`. Categorias de receita: `SALARY`, `INVESTMENTS` e `OTHER`. O formulário filtra também as formas de pagamento: receitas aceitam Pix, transferência, dinheiro e outro; despesas aceitam todas as formas listadas. As regras são validadas também pela API.

O resumo considera apenas transações confirmadas até hoje. A projeção parte desse saldo e soma os lançamentos futuros planejados e as ocorrências recorrentes até a data pedida. Respostas de valores `BigDecimal` são serializadas como números JSON:

```json
{
  "totalBalance": 1250.00,
  "totalIncome": 2000.00,
  "totalExpenses": 750.00
}
```

O retorno da projeção inclui `currentBalance`, `projectedBalance`, `lowestProjectedBalance`, `additionalBalanceNeeded`, `expectedIncome`, `expectedExpenses` e a lista `recurringOccurrences`. `additionalBalanceNeeded` informa quanto seria necessário acrescentar ao saldo atual para não ficar negativo em nenhum dia do período projetado.

Tipos aceitos: `INCOME`, `EXPENSE`. Categorias: `FOOD`, `HOUSING`, `LEISURE`, `TRANSPORT`, `INVESTMENTS`, `OTHER`, `SALARY`, `SUBSCRIPTION` (Assinatura), `INSTALLMENT` (Parcela). Pagamentos: `PIX`, `CREDIT_CARD`, `DEBIT_CARD`, `CASH`, `BANK_TRANSFER`, `OTHER`.

## Testes

```bash
mvn -f backend/pom.xml test
```