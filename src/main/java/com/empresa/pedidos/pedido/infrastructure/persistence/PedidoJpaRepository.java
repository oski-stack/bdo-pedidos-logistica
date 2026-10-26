package com.empresa.pedidos.pedido.infrastructure.persistence;

import com.empresa.pedidos.pedido.domain.EstadoPedido;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio Spring Data de pedidos. La aplicación lo ve a través de {@code PedidoRepositorio}. */
public interface PedidoJpaRepository extends JpaRepository<Pedido, Long> {

    /**
     * Carga las líneas en la misma consulta: con open-in-view desactivado, la respuesta se construye
     * fuera de la transacción y una carga perezosa fallaría.
     */
    @Override
    @EntityGraph(attributePaths = "lineas")
    Optional<Pedido> findById(Long id);

    /**
     * UPDATE condicional atómico de D4. La base de datos bloquea la fila durante el UPDATE: de dos
     * peticiones simultáneas, solo una encuentra el pedido en CREADO y modifica la fila.
     *
     * <p>Un UPDATE masivo no pasa por {@code @Version}, así que la versión se incrementa explícitamente.
     * {@code clearAutomatically}: si hubiera entidades cargadas en el mismo contexto, quedarían obsoletas.
     *
     * @return filas modificadas: 1 si se ha reservado, 0 si no
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Pedido p
               set p.estado = :confirmando, p.confirmacionIniciadaEn = :ahora, p.version = p.version + 1
             where p.id = :id and p.estado = :creado
            """)
    int reservar(@Param("id") Long id, @Param("ahora") Instant ahora,
                 @Param("creado") EstadoPedido creado, @Param("confirmando") EstadoPedido confirmando);
}
