package com.empresa.pedidos.pedido.application;

import com.empresa.pedidos.pedido.application.port.out.ClienteRepositorio;
import com.empresa.pedidos.pedido.application.port.out.PedidoRepositorio;
import com.empresa.pedidos.pedido.domain.LineaPedido;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un pedido para un cliente existente.
 *
 * <p>La transacción cubre la comprobación del cliente y el guardado del pedido con sus líneas: o se
 * guarda todo o nada. No hay llamadas externas, así que no hay motivo para dividirla.
 */
@Service
public class CrearPedidoUseCase {

    private final PedidoRepositorio pedidoRepositorio;
    private final ClienteRepositorio clienteRepositorio;
    // Bean Clock en lugar de Instant.now(): los tests pueden fijar la fecha.
    private final Clock clock;

    public CrearPedidoUseCase(PedidoRepositorio pedidoRepositorio, ClienteRepositorio clienteRepositorio, Clock clock) {
        this.pedidoRepositorio = pedidoRepositorio;
        this.clienteRepositorio = clienteRepositorio;
        this.clock = clock;
    }

    /**
     * @throws ClienteNoEncontradoException si el cliente no existe
     * @throws com.empresa.pedidos.pedido.domain.PedidoInvalidoException si los datos incumplen las reglas del dominio
     */
    @Transactional
    public Pedido crear(CrearPedidoComando comando) {
        if (!clienteRepositorio.existe(comando.clienteId())) {
            throw new ClienteNoEncontradoException(comando.clienteId());
        }

        List<LineaPedido> lineas = comando.lineas().stream()
                .map(l -> new LineaPedido(l.producto(), l.cantidad(), l.precioUnitario(), l.pesoUnitarioKg()))
                .toList();

        Pedido pedido = Pedido.crear(comando.clienteId(), comando.codigoPostalDestino(), lineas, Instant.now(clock));
        return pedidoRepositorio.guardar(pedido);
    }
}
