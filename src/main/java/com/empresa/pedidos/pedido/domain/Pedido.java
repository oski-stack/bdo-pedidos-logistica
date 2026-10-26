package com.empresa.pedidos.pedido.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pedido de un cliente: raíz del agregado formado por el pedido y sus {@link LineaPedido}.
 *
 * <p>Invariantes (D2):
 * <ul>
 *   <li>Se crea con al menos una línea, y las líneas solo se modifican a través del pedido.</li>
 *   <li>{@code importeProductos} se calcula siempre a partir de las líneas; nunca llega del exterior.</li>
 *   <li>Las transiciones de {@link EstadoPedido} se validan aquí (D3).</li>
 *   <li>{@code costeEnvio} e {@code importeTotal} son nulos hasta que el pedido está {@code CONFIRMADO}.</li>
 * </ul>
 * El comportamiento que protege estas reglas se añade en los pasos 2 y 4; en este paso solo existe
 * el mapeo.
 *
 * <p>Concesión consciente (D2): la entidad de dominio lleva anotaciones JPA para no duplicar el
 * modelo con mappers. Depende de {@code jakarta.persistence}, pero no de Spring ni de HTTP.
 */
@Entity
@Table(name = "pedido")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Solo el identificador, sin relación JPA con Cliente: basta con comprobar que existe y se evitan
    // las cargas implícitas y el acoplamiento entre ambos modelos (D2, "Referencia a Cliente").
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;

    @Column(name = "codigo_postal_destino", nullable = false, length = 5)
    private String codigoPostalDestino;

    // Se guarda como texto: reordenar o añadir constantes no corrompe los datos existentes.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPedido estado;

    // Instant + TIMESTAMP WITH TIME ZONE: instante absoluto, independiente de la zona horaria
    // de la JVM o de la base de datos.
    @Column(nullable = false)
    private Instant fecha;

    // Importes en BigDecimal con NUMERIC(19,2): nunca double/float (D7).
    @Column(name = "importe_productos", nullable = false, precision = 19, scale = 2)
    private BigDecimal importeProductos;

    // Nulo hasta CONFIRMADO: lo fija la tarifa del proveedor (D2, D7).
    @Column(name = "coste_envio", precision = 19, scale = 2)
    private BigDecimal costeEnvio;

    // Nulo hasta CONFIRMADO: importeProductos + costeEnvio (D7).
    @Column(name = "importe_total", precision = 19, scale = 2)
    private BigDecimal importeTotal;

    // Momento en que la reserva atómica pasó el pedido a CONFIRMANDO (D4). Solo es una traza:
    // la recuperación de reservas huérfanas queda fuera de alcance.
    @Column(name = "confirmacion_iniciada_en")
    private Instant confirmacionIniciadaEn;

    // Bloqueo optimista para los guardados de la entidad. El UPDATE masivo de
    // reservarParaConfirmacion no pasa por @Version, así que incrementa la versión
    // explícitamente (D4).
    @Version
    @Column(nullable = false)
    private long version;

    // Las líneas no tienen identidad propia: son valores embebidos del agregado.
    // @OrderColumn conserva el orden en que se recibieron.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "linea_pedido", joinColumns = @JoinColumn(name = "pedido_id"))
    @OrderColumn(name = "posicion")
    private List<LineaPedido> lineas = new ArrayList<>();

    protected Pedido() {
        // Requerido por JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getClienteId() {
        return clienteId;
    }

    public String getCodigoPostalDestino() {
        return codigoPostalDestino;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public Instant getFecha() {
        return fecha;
    }

    public BigDecimal getImporteProductos() {
        return importeProductos;
    }

    /** @return el coste de envío, o {@code null} si el pedido aún no está confirmado. */
    public BigDecimal getCosteEnvio() {
        return costeEnvio;
    }

    /** @return el importe total, o {@code null} si el pedido aún no está confirmado. */
    public BigDecimal getImporteTotal() {
        return importeTotal;
    }

    /** @return cuándo se reservó el pedido para confirmarlo, o {@code null} si nunca se ha reservado. */
    public Instant getConfirmacionIniciadaEn() {
        return confirmacionIniciadaEn;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Devuelve una copia inmutable: las líneas solo se modifican a través de {@code Pedido} (D2).
     *
     * @return las líneas del pedido, en el orden en que se recibieron
     */
    public List<LineaPedido> getLineas() {
        return List.copyOf(lineas);
    }
}
