package com.empresa.pedidos.pedido.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.empresa.pedidos.pedido.application.port.out.PedidoRepositorio;
import com.empresa.pedidos.pedido.domain.Pedido;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El pedido y sus líneas se crean en una sola transacción: si algo falla después de insertarlos, no queda
 * nada en la base de datos (requisito funcional 1, D10).
 *
 * <p>El repositorio es el real envuelto en un espía: guarda de verdad (con IDENTITY, el INSERT del pedido
 * se ejecuta en ese momento) y después lanza una excepción, dentro de la transacción del caso de uso.
 * Clase aparte porque el espía cambia el contexto de Spring.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrearPedidoRollbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private PedidoRepositorio pedidoRepositorio;

    @Test
    void siFallaTrasGuardarNoQuedaNiElPedidoNiSusLineas() throws Exception {
        AtomicReference<Long> idInsertado = new AtomicReference<>();
        doAnswer(invocacion -> {
            Pedido guardado = (Pedido) invocacion.callRealMethod();
            idInsertado.set(guardado.getId());
            throw new IllegalStateException("fallo simulado después de guardar");
        }).when(pedidoRepositorio).guardar(any());
        long pedidosAntes = contar("pedido");
        long lineasAntes = contar("linea_pedido");

        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId": 1, "codigoPostalDestino": "28001", "lineas": [
                                  {"producto": "Camiseta", "cantidad": 3, "precioUnitario": 12.50, "pesoUnitarioKg": 0.2}
                                ]}
                                """))
                .andExpect(status().isInternalServerError());

        // El INSERT llegó a ejecutarse (hay id), pero la fila no ha quedado: se ha deshecho.
        assertThat(idInsertado.get()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pedido WHERE id = ?", Long.class, idInsertado.get())).isZero();
        assertThat(contar("pedido")).isEqualTo(pedidosAntes);
        assertThat(contar("linea_pedido")).isEqualTo(lineasAntes);
    }

    private long contar(String tabla) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
    }
}
