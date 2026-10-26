package com.empresa.pedidos.pedido.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Reglas de cálculo y validación del dominio, sin Spring (D1, D2, D7). */
class PedidoTest {

    private static final Instant FECHA = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    void calculaImportePesoYBultosAPartirDeLasLineas() {
        List<LineaPedido> lineas = List.of(
                new LineaPedido("Camiseta", 3, new BigDecimal("12.50"), new BigDecimal("0.2")),
                new LineaPedido("Zapatillas", 1, new BigDecimal("59.99"), new BigDecimal("1.0")));

        Pedido pedido = Pedido.crear(1L, "28001", lineas, FECHA);

        // isEqualTo con BigDecimal compara también la escala: 97.49 y 1.600, no solo el valor.
        assertThat(pedido.getImporteProductos()).isEqualTo(new BigDecimal("97.49"));
        assertThat(pedido.pesoTotalKg()).isEqualTo(new BigDecimal("1.600"));
        assertThat(pedido.numBultos()).isEqualTo(4);
        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(pedido.getFecha()).isEqualTo(FECHA);
        assertThat(pedido.getCosteEnvio()).isNull();
        assertThat(pedido.getImporteTotal()).isNull();
        assertThat(pedido.solicitudTarifa())
                .isEqualTo(new SolicitudTarifa("28001", new BigDecimal("1.600"), 4));
    }

    @Test
    void rechazaUnPedidoSinLineas() {
        assertThatThrownBy(() -> Pedido.crear(1L, "28001", List.of(), FECHA))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessageContaining("al menos una línea");
    }

    @Test
    void rechazaUnaLineaNula() {
        // Arrays.asList admite null; List.of no.
        List<LineaPedido> lineas = Arrays.asList(linea(1, "10.00"), null);

        assertThatThrownBy(() -> Pedido.crear(1L, "28001", lineas, FECHA))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessageContaining("líneas nulas");
    }

    @Test
    void rechazaUnImporteQueNoCabeEnLaColumna() {
        // Precio válido (17 dígitos enteros), pero × 10 da 18 y no cabe en NUMERIC(19,2).
        List<LineaPedido> lineas = List.of(linea(10, "99999999999999999.99"));

        assertThatThrownBy(() -> Pedido.crear(1L, "28001", lineas, FECHA))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessageContaining("importe");
    }

    @Test
    void rechazaUnNumeroDeBultosQueDesborda() {
        List<LineaPedido> lineas = List.of(linea(Integer.MAX_VALUE, "0.01"), linea(1, "0.01"));

        assertThatThrownBy(() -> Pedido.crear(1L, "28001", lineas, FECHA))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessageContaining("bultos");
    }

    private static LineaPedido linea(int cantidad, String precio) {
        return new LineaPedido("Producto", cantidad, new BigDecimal(precio), new BigDecimal("0.001"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineasInvalidas")
    void rechazaLineasInvalidas(String caso, String producto, int cantidad, String precio, String peso) {
        BigDecimal precioUnitario = precio == null ? null : new BigDecimal(precio);
        BigDecimal pesoUnitarioKg = peso == null ? null : new BigDecimal(peso);

        assertThatThrownBy(() -> new LineaPedido(producto, cantidad, precioUnitario, pesoUnitarioKg))
                .isInstanceOf(PedidoInvalidoException.class);
    }

    static Stream<Arguments> lineasInvalidas() {
        return Stream.of(
                Arguments.of("producto vacío", " ", 1, "10.00", "1.0"),
                Arguments.of("cantidad cero", "Camiseta", 0, "10.00", "1.0"),
                Arguments.of("precio cero", "Camiseta", 1, "0", "1.0"),
                Arguments.of("precio nulo", "Camiseta", 1, null, "1.0"),
                Arguments.of("precio con 3 decimales", "Camiseta", 1, "12.505", "1.0"),
                // Misma semántica que @Digits con BigDecimal: cuenta la escala tal como llega (D7).
                Arguments.of("precio con ceros finales de más", "Camiseta", 1, "12.500", "1.0"),
                Arguments.of("peso negativo", "Camiseta", 1, "10.00", "-0.1"),
                Arguments.of("peso con 4 decimales", "Camiseta", 1, "10.00", "0.0005"));
    }
}
