package com.empresa.pedidos.pedido.application.port.out;

import com.empresa.pedidos.pedido.domain.Pedido;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistencia de pedidos vista desde la aplicación (D2). El adaptador vive en
 * {@code infrastructure/persistence}; los casos de uso no conocen Spring Data.
 */
public interface PedidoRepositorio {

    /** Guarda el pedido con sus líneas y devuelve la instancia gestionada, ya con id. */
    Pedido guardar(Pedido pedido);

    /** Carga el pedido con sus líneas, para poder usarlas fuera de una transacción. */
    Optional<Pedido> buscarPorId(Long id);

    /**
     * Reserva atómica para confirmar (D4): pasa el pedido de {@code CREADO} a {@code CONFIRMANDO} si y
     * solo si sigue en {@code CREADO}, en una transacción propia que se confirma antes de volver.
     *
     * @return {@code true} si esta llamada ha hecho la reserva; {@code false} si el pedido no existe o
     *         no estaba en {@code CREADO}
     */
    boolean reservarParaConfirmacion(Long id, Instant ahora);
}
