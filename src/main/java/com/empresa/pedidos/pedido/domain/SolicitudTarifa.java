package com.empresa.pedidos.pedido.domain;

import java.math.BigDecimal;

/**
 * Datos que el proveedor logístico necesita para calcular el coste de envío (D1).
 *
 * <p>Se obtiene con {@link Pedido#solicitudTarifa()}: el peso y los bultos los calcula siempre el pedido.
 *
 * @param codigoPostalDestino código postal de entrega, de 5 dígitos
 * @param pesoKg              peso total en kilogramos, con 3 decimales
 * @param numBultos           número de bultos (un bulto por unidad, regla provisional de D1)
 */
public record SolicitudTarifa(String codigoPostalDestino, BigDecimal pesoKg, int numBultos) {
}
