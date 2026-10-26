package com.empresa.pedidos.pedido.domain;

import java.math.BigDecimal;

/**
 * Tarifa de envío ya validada: coste en euros, no negativo y con 2 decimales (D5, D7).
 *
 * <p>La validación de la respuesta del proveedor la hace el adaptador HTTP antes de construirla. El
 * contrato también devuelve {@code plazoDias}, pero el modelo del pedido no lo guarda, así que no se
 * incluye.
 *
 * @param coste coste del envío en EUR
 */
public record TarifaEnvio(BigDecimal coste) {
}
