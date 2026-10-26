package com.empresa.pedidos.pedido.infrastructure.persistence;

import com.empresa.pedidos.pedido.domain.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio Spring Data de pedidos. La aplicación lo ve a través de {@code PedidoRepositorio}. */
public interface PedidoJpaRepository extends JpaRepository<Pedido, Long> {
}
