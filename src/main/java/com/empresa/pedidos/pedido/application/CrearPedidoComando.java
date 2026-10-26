package com.empresa.pedidos.pedido.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Datos de entrada para crear un pedido. No incluye importes: los calcula el dominio (D7).
 *
 * <p>Desacopla el caso de uso de los DTOs web: el controller traduce la petición a este comando.
 */
public record CrearPedidoComando(Long clienteId, String codigoPostalDestino, List<Linea> lineas) {

    public record Linea(String producto, int cantidad, BigDecimal precioUnitario, BigDecimal pesoUnitarioKg) {
    }
}
