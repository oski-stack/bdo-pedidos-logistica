package com.empresa.pedidos.pedido.infrastructure.proveedor;

import java.math.BigDecimal;

/**
 * Respuesta 200 del proveedor. {@code coste} es {@code BigDecimal}: Jackson lo lee del texto JSON sin
 * pasar por {@code double} (D7). Se valida en el adaptador antes de convertirla en {@code TarifaEnvio}.
 */
record TarifaResponse(BigDecimal coste, String moneda, Integer plazoDias) {
}
