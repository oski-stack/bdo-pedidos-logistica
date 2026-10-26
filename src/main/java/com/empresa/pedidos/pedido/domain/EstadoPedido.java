package com.empresa.pedidos.pedido.domain;

/**
 * Estados de un {@link Pedido} (D3).
 *
 * <pre>
 * CREADO ──reservar──▶ CONFIRMANDO ──éxito──▶ CONFIRMADO
 *    ▲                      │
 *    └──────fallo───────────┘
 * </pre>
 *
 * {@code CANCELADO} queda fuera de alcance porque ningún endpoint lo usa.
 */
public enum EstadoPedido {

    /** Pedido creado y pendiente de confirmar. También es el estado al que vuelve si falla el proveedor. */
    CREADO,

    /**
     * Reserva en curso: la ha tomado una petición de confirmación mediante el UPDATE condicional
     * atómico, e impide que otra confirmación concurrente llame al proveedor (D4).
     */
    CONFIRMANDO,

    /** Confirmado con la tarifa del proveedor: el coste de envío y el importe total ya están fijados. */
    CONFIRMADO
}
