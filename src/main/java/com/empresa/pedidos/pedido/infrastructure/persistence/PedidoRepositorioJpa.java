package com.empresa.pedidos.pedido.infrastructure.persistence;

import com.empresa.pedidos.pedido.application.port.out.PedidoRepositorio;
import com.empresa.pedidos.pedido.domain.EstadoPedido;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adaptador del puerto {@link PedidoRepositorio} con Spring Data JPA (D2). */
@Repository
class PedidoRepositorioJpa implements PedidoRepositorio {

    private final PedidoJpaRepository jpa;

    PedidoRepositorioJpa(PedidoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Pedido guardar(Pedido pedido) {
        return jpa.save(pedido);
    }

    @Override
    public Optional<Pedido> buscarPorId(Long id) {
        return jpa.findById(id);
    }

    /**
     * Transacción propia y corta: el caso de uso la llama sin transacción abierta, así que el commit se
     * hace al volver de este método, antes de llamar al proveedor (D4). Es un método público llamado desde
     * otro bean, así que el proxy de {@code @Transactional} sí lo intercepta.
     */
    @Override
    @Transactional
    public boolean reservarParaConfirmacion(Long id, Instant ahora) {
        return jpa.reservar(id, ahora, EstadoPedido.CREADO, EstadoPedido.CONFIRMANDO) == 1;
    }
}
