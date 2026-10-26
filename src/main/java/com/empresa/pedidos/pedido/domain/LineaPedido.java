package com.empresa.pedidos.pedido.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * Línea de un {@link Pedido}: objeto de valor embebido, sin identidad propia.
 *
 * <p>Solo existe como parte del agregado {@code Pedido} y se persiste en la tabla
 * {@code linea_pedido} mediante {@code @ElementCollection}.
 */
@Embeddable
public class LineaPedido {

    @Column(name = "producto", nullable = false, length = 160)
    private String producto;

    @Column(name = "cantidad", nullable = false)
    private int cantidad;

    // BigDecimal con NUMERIC(19,2): la entrada admite como mucho 2 decimales, así que
    // precioUnitario × cantidad es exacto (D7).
    @Column(name = "precio_unitario", nullable = false, precision = 19, scale = 2)
    private BigDecimal precioUnitario;

    // Peso en kilogramos con 3 decimales (gramos). Es BigDecimal, nunca double, y se usa
    // para calcular el peso que se envía al proveedor de tarifas (D1, D7).
    @Column(name = "peso_unitario_kg", nullable = false, precision = 10, scale = 3)
    private BigDecimal pesoUnitarioKg;

    protected LineaPedido() {
        // Requerido por JPA.
    }

    /**
     * @param producto       nombre o descripción del producto
     * @param cantidad       unidades pedidas
     * @param precioUnitario precio de una unidad, con 2 decimales como máximo (D7)
     * @param pesoUnitarioKg peso de una unidad en kilogramos
     */
    public LineaPedido(String producto, int cantidad, BigDecimal precioUnitario, BigDecimal pesoUnitarioKg) {
        this.producto = producto;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.pesoUnitarioKg = pesoUnitarioKg;
    }

    public String getProducto() {
        return producto;
    }

    public int getCantidad() {
        return cantidad;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getPesoUnitarioKg() {
        return pesoUnitarioKg;
    }
}
