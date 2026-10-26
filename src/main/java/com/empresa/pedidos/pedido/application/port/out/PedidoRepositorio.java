package com.empresa.pedidos.pedido.application.port.out;

import com.empresa.pedidos.pedido.domain.Pedido;
import java.util.Optional;

/**
 * Persistencia de pedidos vista desde la aplicación (D2). El adaptador vive en
 * {@code infrastructure/persistence}; los casos de uso no conocen Spring Data.
 *
 * <p>{@code reservarParaConfirmacion} se añade en el paso 4 (D4).
 */
public interface PedidoRepositorio {

    /** Guarda el pedido con sus líneas y devuelve la instancia gestionada, ya con id. */
    Pedido guardar(Pedido pedido);

    Optional<Pedido> buscarPorId(Long id);
}
