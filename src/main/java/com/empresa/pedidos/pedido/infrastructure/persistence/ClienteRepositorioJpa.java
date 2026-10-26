package com.empresa.pedidos.pedido.infrastructure.persistence;

import com.empresa.pedidos.cliente.infrastructure.persistence.ClienteJpaRepository;
import com.empresa.pedidos.pedido.application.port.out.ClienteRepositorio;
import org.springframework.stereotype.Repository;

/** Adaptador del puerto {@link ClienteRepositorio}: solo comprueba la existencia, sin cargar la entidad. */
@Repository
class ClienteRepositorioJpa implements ClienteRepositorio {

    private final ClienteJpaRepository jpa;

    ClienteRepositorioJpa(ClienteJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existe(Long id) {
        return jpa.existsById(id);
    }
}
