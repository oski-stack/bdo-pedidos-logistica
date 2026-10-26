package com.empresa.pedidos.pedido.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/pedidos}. No contiene importes: los calcula el servidor (D7).
 *
 * <p>{@code @NotNull} en el elemento de la lista: {@code @Valid} no valida elementos nulos y
 * {@code "lineas": [null]} pasaría {@code @NotEmpty}.
 */
public record CrearPedidoRequest(
        @NotNull Long clienteId,
        // @Pattern acepta null; @NotNull hace que la ausencia dé 400 de validación y no un error del dominio.
        @NotNull @Pattern(regexp = "\\d{5}", message = "debe tener 5 dígitos") String codigoPostalDestino,
        @NotEmpty List<@NotNull @Valid LineaPedidoRequest> lineas) {
}
