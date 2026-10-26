package com.empresa.pedidos.pedido.infrastructure.web;

import com.empresa.pedidos.pedido.domain.EstadoPedido;
import com.empresa.pedidos.pedido.domain.LineaPedido;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Representación de un pedido en la API. {@code costeEnvio} e {@code importeTotal} son nulos hasta que
 * el pedido está confirmado (D2).
 */
public record PedidoResponse(
        Long id,
        Long clienteId,
        String codigoPostalDestino,
        EstadoPedido estado,
        Instant fecha,
        BigDecimal importeProductos,
        BigDecimal costeEnvio,
        BigDecimal importeTotal,
        List<Linea> lineas) {

    public record Linea(String producto, int cantidad, BigDecimal precioUnitario, BigDecimal pesoUnitarioKg,
                        BigDecimal subtotal) {

        static Linea desde(LineaPedido linea) {
            return new Linea(linea.getProducto(), linea.getCantidad(), linea.getPrecioUnitario(),
                    linea.getPesoUnitarioKg(), linea.subtotal());
        }
    }

    static PedidoResponse desde(Pedido pedido) {
        return new PedidoResponse(
                pedido.getId(),
                pedido.getClienteId(),
                pedido.getCodigoPostalDestino(),
                pedido.getEstado(),
                pedido.getFecha(),
                pedido.getImporteProductos(),
                pedido.getCosteEnvio(),
                pedido.getImporteTotal(),
                pedido.getLineas().stream().map(Linea::desde).toList());
    }
}
