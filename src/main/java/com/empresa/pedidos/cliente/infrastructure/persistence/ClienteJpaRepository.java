package com.empresa.pedidos.cliente.infrastructure.persistence;

import com.empresa.pedidos.cliente.domain.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio Spring Data de clientes. Solo lo usan adaptadores de infraestructura. */
public interface ClienteJpaRepository extends JpaRepository<Cliente, Long> {
}
