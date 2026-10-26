package com.empresa.pedidos.pedido.application;

import com.empresa.pedidos.pedido.application.port.out.PedidoRepositorio;
import com.empresa.pedidos.pedido.application.port.out.ProveedorTarifas;
import com.empresa.pedidos.pedido.domain.Pedido;
import com.empresa.pedidos.pedido.domain.TarifaEnvio;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirma un pedido con la tarifa del proveedor logístico (D3, D4, D5).
 *
 * <p>Deliberadamente <strong>sin {@code @Transactional}</strong>: la llamada al proveedor puede tardar
 * varios segundos y no debe ocupar una transacción ni una conexión del pool. El flujo tiene tres fases:
 * <ol>
 *   <li><b>Reserva</b> atómica {@code CREADO → CONFIRMANDO}, en una transacción corta que se confirma antes
 *       de seguir. Solo una petición concurrente la consigue.</li>
 *   <li><b>Llamada al proveedor</b>, sin transacción.</li>
 *   <li><b>Finalización</b> ({@code confirmar}) o <b>liberación</b> ({@code revertirConfirmacion}), en una
 *       transacción nueva que vuelve a cargar el pedido; {@code @Version} protege el guardado.</li>
 * </ol>
 * Las transacciones de la fase 3 usan {@link TransactionTemplate}: un {@code @Transactional} en un
 * método privado de esta clase no lo interceptaría el proxy de Spring.
 */
@Service
public class ConfirmarPedidoUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmarPedidoUseCase.class);

    private final PedidoRepositorio pedidoRepositorio;
    private final ProveedorTarifas proveedorTarifas;
    private final TransactionTemplate transaccion;
    private final Clock clock;

    public ConfirmarPedidoUseCase(PedidoRepositorio pedidoRepositorio, ProveedorTarifas proveedorTarifas,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.pedidoRepositorio = pedidoRepositorio;
        this.proveedorTarifas = proveedorTarifas;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @return el pedido {@code CONFIRMADO}, también si ya lo estaba (idempotente, sin llamar al proveedor)
     * @throws PedidoNoEncontradoException  si el pedido no existe
     * @throws ConfirmacionEnCursoException si otra petición lo está confirmando
     * @throws com.empresa.pedidos.pedido.application.port.out.ProveedorNoDisponibleException     si el
     *         proveedor no está disponible; el pedido vuelve a {@code CREADO}
     * @throws com.empresa.pedidos.pedido.application.port.out.ErrorIntegracionProveedorException si el
     *         proveedor rechaza la petición o responde algo inválido; el pedido vuelve a {@code CREADO}
     */
    public Pedido confirmar(Long pedidoId) {
        // Fase 1: la reserva se confirma (commit) al volver de este método.
        if (!pedidoRepositorio.reservarParaConfirmacion(pedidoId, Instant.now(clock))) {
            return resolverSinReserva(pedidoId);
        }

        // Fase 2: sin transacción. Cualquier fallo libera la reserva para poder reintentar (D3).
        TarifaEnvio tarifa;
        try {
            tarifa = proveedorTarifas.consultarTarifa(cargar(pedidoId).solicitudTarifa());
        } catch (RuntimeException fallo) {
            liberar(pedidoId, fallo);
            throw fallo;
        }

        // Fase 3: transacción nueva; el pedido se vuelve a cargar para guardar con su versión actual.
        return transaccion.execute(estado -> {
            Pedido pedido = cargar(pedidoId);
            pedido.confirmar(tarifa);
            return pedidoRepositorio.guardar(pedido);
        });
    }

    /**
     * La reserva no ha modificado ninguna fila: el pedido no existe o no estaba en {@code CREADO} (D5).
     */
    private Pedido resolverSinReserva(Long pedidoId) {
        Pedido pedido = cargar(pedidoId);
        return switch (pedido.getEstado()) {
            case CONFIRMADO -> pedido;
            case CONFIRMANDO -> throw new ConfirmacionEnCursoException(pedidoId);
            // Estaba en CONFIRMANDO al hacer la reserva y otra petición lo ha liberado justo después.
            // También es un conflicto: el cliente puede reintentar.
            case CREADO -> throw new ConfirmacionEnCursoException(pedidoId);
        };
    }

    // Si la liberación también falla, el pedido queda en CONFIRMANDO: es el caso de reserva huérfana que
    // D4 deja fuera de alcance. Se conserva el fallo original y se adjunta el de la liberación.
    private void liberar(Long pedidoId, RuntimeException fallo) {
        try {
            transaccion.executeWithoutResult(estado -> {
                Pedido pedido = cargar(pedidoId);
                pedido.revertirConfirmacion();
                pedidoRepositorio.guardar(pedido);
            });
        } catch (RuntimeException errorAlLiberar) {
            log.error("No se pudo liberar la reserva del pedido {}; queda en CONFIRMANDO", pedidoId, errorAlLiberar);
            fallo.addSuppressed(errorAlLiberar);
        }
    }

    private Pedido cargar(Long pedidoId) {
        return pedidoRepositorio.buscarPorId(pedidoId).orElseThrow(() -> new PedidoNoEncontradoException(pedidoId));
    }
}
