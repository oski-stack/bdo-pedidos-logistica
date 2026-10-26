package com.empresa.pedidos.pedido.application.port.out;

import com.empresa.pedidos.pedido.domain.SolicitudTarifa;
import com.empresa.pedidos.pedido.domain.TarifaEnvio;

/**
 * Proveedor logístico externo que calcula el coste de envío (D2). El adaptador vive en
 * {@code infrastructure/proveedor} y se encarga de los timeouts, los reintentos y la validación de la
 * respuesta (D6).
 */
public interface ProveedorTarifas {

    /**
     * @return la tarifa ya validada
     * @throws ProveedorNoDisponibleException si el proveedor no responde a tiempo o falla por su lado
     * @throws ErrorIntegracionProveedorException si rechaza la petición (4xx) o la respuesta no es válida
     */
    TarifaEnvio consultarTarifa(SolicitudTarifa solicitud);
}
