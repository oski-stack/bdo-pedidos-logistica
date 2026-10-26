package com.empresa.pedidos.pedido.infrastructure.web;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Línea de la petición de creación (D1, D7).
 *
 * <p>Precio y peso son {@code BigDecimal}: Jackson los lee directamente del texto JSON, sin pasar por
 * {@code double}. Un precio con más de 2 decimales se rechaza, no se redondea (D7).
 */
public record LineaPedidoRequest(
        @NotBlank String producto,
        @NotNull @Positive Integer cantidad,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal precioUnitario,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal pesoUnitarioKg) {
}
