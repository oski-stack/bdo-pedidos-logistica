package com.empresa.pedidos.pedido.infrastructure.web;

import com.empresa.pedidos.pedido.application.CrearPedidoComando;
import com.empresa.pedidos.pedido.application.ConfirmarPedidoUseCase;
import com.empresa.pedidos.pedido.application.CrearPedidoUseCase;
import com.empresa.pedidos.pedido.domain.Pedido;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/pedidos")
class PedidoController {

    private final CrearPedidoUseCase crearPedido;
    private final ConfirmarPedidoUseCase confirmarPedido;

    PedidoController(CrearPedidoUseCase crearPedido, ConfirmarPedidoUseCase confirmarPedido) {
        this.crearPedido = crearPedido;
        this.confirmarPedido = confirmarPedido;
    }

    /** Crea un pedido en estado {@code CREADO}: 201 con la cabecera {@code Location} y el pedido en el cuerpo. */
    @PostMapping
    ResponseEntity<PedidoResponse> crear(@Valid @RequestBody CrearPedidoRequest request) {
        Pedido pedido = crearPedido.crear(aComando(request));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(pedido.getId())
                .toUri();
        return ResponseEntity.created(location).body(PedidoResponse.desde(pedido));
    }

    /**
     * Confirma el pedido con la tarifa del proveedor: 200 con el pedido {@code CONFIRMADO}, también si ya
     * lo estaba (D5). Los fallos los traduce {@link ManejadorErrores}.
     */
    @PostMapping("/{id}/confirmacion")
    PedidoResponse confirmar(@PathVariable Long id) {
        return PedidoResponse.desde(confirmarPedido.confirmar(id));
    }

    private static CrearPedidoComando aComando(CrearPedidoRequest request) {
        return new CrearPedidoComando(
                request.clienteId(),
                request.codigoPostalDestino(),
                request.lineas().stream()
                        .map(l -> new CrearPedidoComando.Linea(
                                l.producto(), l.cantidad(), l.precioUnitario(), l.pesoUnitarioKg()))
                        .toList());
    }
}
