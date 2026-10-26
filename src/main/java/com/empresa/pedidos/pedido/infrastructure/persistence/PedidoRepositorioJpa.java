package com.empresa.pedidos.pedido.infrastructure.persistence;

import com.empresa.pedidos.pedido.application.port.out.PedidoRepositorio;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.util.Optional;
import org.springframework.stereotype.Repository;

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
}
