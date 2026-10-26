package com.empresa.pedidos.pedido.infrastructure.web;

import com.empresa.pedidos.pedido.application.CrearPedidoComando;
import com.empresa.pedidos.pedido.application.CrearPedidoUseCase;
import com.empresa.pedidos.pedido.domain.Pedido;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/pedidos")
class PedidoController {

    private final CrearPedidoUseCase crearPedido;

    PedidoController(CrearPedidoUseCase crearPedido) {
        this.crearPedido = crearPedido;
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
