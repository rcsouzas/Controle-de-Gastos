package br.com.controlegastos.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InstallmentRequest(
        @NotNull @Valid Transaction transaction,
        @Min(value = 1, message = "A quantidade deve ser de pelo menos 1 parcela")
        @Max(value = 120, message = "A quantidade máxima é de 120 parcelas")
        int count) {
}
