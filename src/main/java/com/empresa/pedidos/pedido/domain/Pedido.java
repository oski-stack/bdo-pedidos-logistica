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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

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
 * La creación pasa siempre por {@link #crear}. Las transiciones de confirmación se añaden en el paso 4.
 *
 * <p>Concesión consciente (D2): la entidad de dominio lleva anotaciones JPA para no duplicar el
 * modelo con mappers. Depende de {@code jakarta.persistence}, pero no de Spring ni de HTTP.
 */
@Entity
@Table(name = "pedido")
public class Pedido {

    // Dígitos enteros que caben en importe_productos NUMERIC(19,2). @Digits limita el precio unitario,
    // pero no el subtotal ni la suma: sin esta comprobación el INSERT fallaría con un 500 (D7).
    private static final int DIGITOS_ENTEROS_IMPORTE = 17;

    // Código postal español: 5 dígitos (D1).
    private static final Pattern CODIGO_POSTAL = Pattern.compile("\\d{5}");

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

    /**
     * Crea un pedido en estado {@code CREADO} con su importe de productos calculado a partir de las
     * líneas. El coste de envío y el importe total quedan nulos hasta la confirmación (D2).
     *
     * @param clienteId           cliente que hace el pedido; su existencia la comprueba el caso de uso
     * @param codigoPostalDestino código postal de entrega, de 5 dígitos (D1)
     * @param lineas              al menos una línea
     * @param fecha               instante de creación; lo aporta el llamante para poder fijarlo en los tests
     * @throws PedidoInvalidoException si algún dato incumple las reglas
     */
    public static Pedido crear(Long clienteId, String codigoPostalDestino, List<LineaPedido> lineas, Instant fecha) {
        if (clienteId == null) {
            throw new PedidoInvalidoException("El cliente es obligatorio");
        }
        if (codigoPostalDestino == null || !CODIGO_POSTAL.matcher(codigoPostalDestino).matches()) {
            throw new PedidoInvalidoException("El código postal de destino debe tener 5 dígitos");
        }
        if (lineas == null || lineas.isEmpty()) {
            throw new PedidoInvalidoException("El pedido debe tener al menos una línea");
        }
        // stream().anyMatch y no contains(null): List.of(...).contains(null) lanza NullPointerException.
        if (lineas.stream().anyMatch(Objects::isNull)) {
            throw new PedidoInvalidoException("El pedido no puede tener líneas nulas");
        }
        if (fecha == null) {
            throw new PedidoInvalidoException("La fecha del pedido es obligatoria");
        }

        Pedido pedido = new Pedido();
        pedido.clienteId = clienteId;
        pedido.codigoPostalDestino = codigoPostalDestino;
        pedido.estado = EstadoPedido.CREADO;
        pedido.fecha = fecha;
        // Copia defensiva: quien llama no puede modificar las líneas después (D2).
        pedido.lineas = new ArrayList<>(lineas);
        pedido.importeProductos = pedido.calcularImporteProductos();
        // Se calcula ya para que un desbordamiento se rechace al crear y no al confirmar.
        pedido.numBultos();
        return pedido;
    }

    // Suma de subtotales. Cada subtotal es exacto con 2 decimales, así que el setScale no redondea:
    // solo fija la escala de la columna NUMERIC(19,2) (D7).
    private BigDecimal calcularImporteProductos() {
        BigDecimal importe = lineas.stream()
                .map(LineaPedido::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
        if (importe.precision() - importe.scale() > DIGITOS_ENTEROS_IMPORTE) {
            throw new PedidoInvalidoException("El importe del pedido supera el máximo admitido");
        }
        return importe;
    }

    /**
     * Peso total que se envía al proveedor de tarifas: {@code Σ (cantidad × pesoUnitarioKg)} (D1).
     *
     * @return el peso en kilogramos, con 3 decimales
     */
    public BigDecimal pesoTotalKg() {
        return lineas.stream()
                .map(LineaPedido::pesoKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.UNNECESSARY);
    }

    /**
     * Número de bultos que se envía al proveedor de tarifas: un bulto por unidad, {@code Σ cantidad}.
     *
     * <p>Regla de negocio provisional (D1): está aislada en este método para poder cambiarla sin
     * tocar el resto.
     *
     * @throws PedidoInvalidoException si la suma no cabe en un {@code int}
     */
    public int numBultos() {
        int bultos = 0;
        for (LineaPedido linea : lineas) {
            try {
                bultos = Math.addExact(bultos, linea.getCantidad());
            } catch (ArithmeticException desbordamiento) {
                throw new PedidoInvalidoException("El número de bultos supera el máximo admitido");
            }
        }
        return bultos;
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
