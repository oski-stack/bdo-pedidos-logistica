package com.empresa.pedidos.pedido.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
     * Crea una línea válida o lanza {@link PedidoInvalidoException} (D1, D7).
     *
     * @param producto       nombre o descripción del producto; no puede estar vacío
     * @param cantidad       unidades pedidas; mayor que cero
     * @param precioUnitario precio de una unidad; mayor que cero y con 2 decimales como máximo (D7)
     * @param pesoUnitarioKg peso de una unidad en kilogramos; mayor que cero y con 3 decimales como máximo
     */
    public LineaPedido(String producto, int cantidad, BigDecimal precioUnitario, BigDecimal pesoUnitarioKg) {
        if (producto == null || producto.isBlank()) {
            throw new PedidoInvalidoException("El producto de la línea es obligatorio");
        }
        if (cantidad <= 0) {
            throw new PedidoInvalidoException("La cantidad debe ser mayor que cero");
        }
        this.producto = producto;
        this.cantidad = cantidad;
        this.precioUnitario = positivoConEscala(precioUnitario, 2, "El precio unitario");
        this.pesoUnitarioKg = positivoConEscala(pesoUnitarioKg, 3, "El peso unitario");
    }

    /**
     * Comprueba que el valor es positivo y no tiene más decimales de los permitidos, y lo devuelve con
     * exactamente esa escala ({@code 12.5} → {@code 12.50}).
     *
     * <p>Los valores con más decimales se rechazan, no se redondean (D7). Se cuenta la escala tal como
     * llega, igual que {@code @Digits} con un {@code BigDecimal}: {@code 12.500} también se rechaza. Por
     * eso {@code RoundingMode.UNNECESSARY} nunca llega a redondear.
     */
    private static BigDecimal positivoConEscala(BigDecimal valor, int decimalesMaximos, String campo) {
        if (valor == null || valor.signum() <= 0) {
            throw new PedidoInvalidoException(campo + " debe ser mayor que cero");
        }
        if (valor.scale() > decimalesMaximos) {
            throw new PedidoInvalidoException(campo + " admite como máximo " + decimalesMaximos + " decimales");
        }
        return valor.setScale(decimalesMaximos, RoundingMode.UNNECESSARY);
    }

    /** @return {@code precioUnitario × cantidad}; exacto porque el precio tiene como mucho 2 decimales (D7). */
    public BigDecimal subtotal() {
        return precioUnitario.multiply(BigDecimal.valueOf(cantidad));
    }

    /** @return {@code pesoUnitarioKg × cantidad}, en kilogramos (D1). */
    public BigDecimal pesoKg() {
        return pesoUnitarioKg.multiply(BigDecimal.valueOf(cantidad));
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
