package com.empresa.pedidos.pedido.infrastructure.proveedor;

import com.empresa.pedidos.pedido.domain.SolicitudTarifa;
import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/tarifas} según el contrato del proveedor. Es independiente del dominio
 * para que un cambio en el contrato no se propague a {@link SolicitudTarifa}.
 */
record TarifaRequest(String codigoPostalDestino, BigDecimal pesoKg, int numBultos) {

    static TarifaRequest desde(SolicitudTarifa solicitud) {
        return new TarifaRequest(solicitud.codigoPostalDestino(), solicitud.pesoKg(), solicitud.numBultos());
    }
}
