# Pedidos con integración logística

Servicio de pedidos que calcula el coste de envío llamando a un proveedor logístico externo. Prueba técnica Java Senior.

- `POST /api/pedidos` crea un pedido con sus líneas en una sola transacción; el importe lo calcula el servidor.
- `POST /api/pedidos/{id}/confirmacion` pide la tarifa al proveedor, guarda el coste de envío y el total.

Las decisiones completas, con alternativas y motivos, están en [`docs/decisiones.md`](docs/decisiones.md) (D1–D10). Este README las resume.

## Requisitos

- JDK 17
- Maven 3.8+

## Tests

```bash
mvn verify
```

No necesitan variables de entorno ni secretos: el token ficticio y unos timeouts cortos están en `src/test/resources/config/application.yml`, que Spring carga además del `application.yml` principal (igual con Maven que desde el IDE).

| Test | Tipo | Qué demuestra |
|---|---|---|
| `PedidoTest` | Unitario, sin Spring | Importe de productos, peso, bultos, **total con envío (97,49 + 7,95 = 105,44)**, transiciones de estado y validaciones |
| `CrearPedidoIntegracionTest`, `CrearPedidoRollbackTest` | Integración (MockMvc + H2) | 201 y pedido persistido; 400/422; **rollback** si algo falla tras insertar |
| `ProveedorTarifasHttpTest` | Adaptador aislado + WireMock | Token, reintento solo de 503 y fallos de conexión, timeouts, 4xx, respuestas inválidas |
| `ConfiguracionProveedorTest` | Contexto mínimo | La app **no arranca sin token** ni con configuración inválida; el token no aparece en logs ni errores |
| `ConfirmarPedidoIntegracionTest` | Integración + WireMock | **Timeout → 503 sin reintento y pedido en `CREADO`**; 503 → 200 con 2 llamadas; 401 → 502; idempotencia |
| `ConfirmacionConcurrenteTest` | Integración, BD real, sin `@Transactional` | **Confirmaciones simultáneas: 1 sola llamada al proveedor**, 409 para las demás y total correcto |

## Arranque

El token del proveedor se lee **solo** de la variable de entorno `PROVEEDOR_TOKEN`; sin ella la aplicación no arranca. La URL del proveedor se puede cambiar con `PROVEEDOR_URL` (por defecto `http://localhost:8089`).

No existe un proveedor real. Para probar la confirmación a mano, levanta un WireMock con el stub de `docs/wiremock` (el jar ya está en el repositorio local de Maven después de `mvn verify`):

```bash
# Terminal 1: proveedor simulado (200 con token, 401 sin él)
java -jar ~/.m2/repository/org/wiremock/wiremock-standalone/3.9.1/wiremock-standalone-3.9.1.jar \
     --port 8089 --root-dir docs/wiremock

# Terminal 2: aplicación
PROVEEDOR_TOKEN=token-demo mvn spring-boot:run
```

En PowerShell: `$env:PROVEEDOR_TOKEN = "token-demo"; mvn spring-boot:run`.

H2 en memoria; Flyway crea el esquema y carga dos clientes de demostración (ids 1 y 2).

```bash
curl -i -X POST http://localhost:8080/api/pedidos -H 'Content-Type: application/json' -d '{
  "clienteId": 1, "codigoPostalDestino": "43840",
  "lineas": [
    {"producto": "Camiseta",   "cantidad": 3, "precioUnitario": 12.50, "pesoUnitarioKg": 0.2},
    {"producto": "Zapatillas", "cantidad": 1, "precioUnitario": 59.99, "pesoUnitarioKg": 1.0}
  ]}'
# 201, estado CREADO, importeProductos 97.49

curl -i -X POST http://localhost:8080/api/pedidos/1/confirmacion
# 200, estado CONFIRMADO, costeEnvio 7.95, importeTotal 105.44
```

## Decisiones principales

### Arquitectura (D2)
Monolito con hexagonal ligera y paquetes por funcionalidad. Los casos de uso dependen solo de puertos propios (`application/port/out`): `PedidoRepositorio`, `ClienteRepositorio` y `ProveedorTarifas`. Spring Data y el cliente HTTP quedan en adaptadores. Concesión consciente: las entidades de dominio llevan anotaciones JPA para no duplicar el modelo con mappers. `Pedido` guarda `clienteId`, sin relación JPA con `Cliente`.

### Estados del pedido (D3)
```
CREADO ──reserva──▶ CONFIRMANDO ──éxito──▶ CONFIRMADO
   ▲                     │
   └──────fallo──────────┘
```
`CONFIRMANDO` es la reserva que impide confirmaciones concurrentes. Si el proveedor falla, el pedido vuelve a `CREADO` y se puede reintentar. `costeEnvio` e `importeTotal` son nulos hasta `CONFIRMADO`.

### Importes (D7)
`BigDecimal` en todo el código y `NUMERIC(19,2)` en la base de datos; el peso, `NUMERIC(10,3)`. Los precios con más de 2 decimales **se rechazan, no se redondean**. El cliente nunca envía importes. El coste del proveedor se lee directamente a `BigDecimal`, sin pasar por `double`.

### Resiliencia frente al proveedor (D5, D6)
- **Timeouts por intento:** conexión 1 s y lectura 6 s (el proveedor puede tardar hasta 5 s). Con `JdkClientHttpRequestFactory` el de lectura cubre todo el intercambio, por eso la configuración exige lectura > conexión.
- **Reintentos:** 2 intentos como máximo con backoff exponencial y jitter (Resilience4j), **solo** ante un 503 o un fallo de conexión en el que la petición no llegó a enviarse. No se reintenta tras un timeout de lectura (ya se gastó un intento), ni ante 4xx, otros 5xx o respuestas inválidas.
- **Respuesta y estado final:**

| Situación | HTTP | Pedido |
|---|---|---|
| Éxito | 200 | `CONFIRMADO` |
| Ya confirmado | 200, sin llamar al proveedor | `CONFIRMADO` |
| Otra confirmación en curso | 409 | sin cambios |
| Timeout, 503 tras reintentos u otro 5xx | 503 + `Retry-After: 30` | `CREADO` |
| 4xx del proveedor (p. ej. token inválido) o respuesta inválida | 502 | `CREADO` |
| Pedido inexistente | 404 | — |

El 4xx del proveedor es un 502 porque es un fallo de **nuestra** integración, no del consumidor.

### Concurrencia (D4)
1. **Reserva atómica** en una transacción corta que se confirma **antes** de llamar al proveedor:
   `UPDATE pedido SET estado='CONFIRMANDO', version=version+1 … WHERE id=:id AND estado='CREADO'`.
   La base de datos bloquea la fila durante el UPDATE: de dos peticiones simultáneas solo una modifica 1 fila; la otra recibe 409 sin llamar al proveedor.
2. **Llamada al proveedor sin transacción abierta**: no ocupa una conexión del pool durante segundos.
3. **Finalización o liberación** en una transacción nueva que vuelve a cargar el pedido; `@Version` protege el guardado. La versión se incrementa a mano en el UPDATE porque un UPDATE masivo no pasa por el bloqueo optimista de JPA.

Descartados: `synchronized` (no funciona con varias instancias) y `SELECT … FOR UPDATE` durante la llamada (mantiene la transacción y la conexión abiertas). Una reserva con la entidad y `@Version` en transacción corta también sería válida; se eligió el UPDATE condicional por claridad ("1 fila o 0 filas").

### Token y configuración (D8)
El token viene de `PROVEEDOR_TOKEN` a través de un `@ConfigurationProperties` validado: si falta o tiene un formato inválido (por ejemplo, un salto de línea final), la aplicación no arranca, y el error no muestra el valor. Nunca se registra: el `toString` de las propiedades lo oculta y los logs del adaptador solo incluyen códigos de estado o tipos de excepción. Las respuestas de error al consumidor llevan un mensaje fijo, sin el cuerpo del proveedor. URL, timeouts, intentos y backoff también están externalizados.

### Supuestos sobre los datos del proveedor (D1)
El modelo del enunciado no incluye código postal, peso ni bultos, que el proveedor exige. **Supuesto pendiente de confirmar:**
- `codigoPostalDestino` (5 dígitos) se envía al crear el pedido.
- Cada línea tiene `pesoUnitarioKg`; `pesoKg = Σ (cantidad × pesoUnitarioKg)`.
- `numBultos = Σ cantidad` (un bulto por unidad). Es una regla provisional aislada en `Pedido.numBultos()` para poder cambiarla sin tocar el resto.

## Qué queda fuera y cómo lo abordaría

| Tema | Cómo lo abordaría |
|---|---|
| **Reservas huérfanas:** si el proceso cae durante la confirmación, el pedido queda en `CONFIRMANDO` | Un identificador de intento (`confirmacionIntentoId`) que condicione las escrituras de finalización, más un proceso de recuperación. No se recupera por antigüedad: un proceso pausado podría seguir vivo y provocar una segunda llamada (D4). |
| Idempotencia del cliente | Cabecera `Idempotency-Key` en la confirmación. |
| Presupuesto global de tiempo, circuit breaker | `TimeLimiter` y `CircuitBreaker` de Resilience4j cuando haya datos reales del proveedor. |
| Confirmación asíncrona | 202 + outbox y un worker, si la latencia síncrona (hasta ~12 s en el peor caso) no es aceptable. |
| PostgreSQL | El comportamiento concurrente está demostrado en H2; en PostgreSQL es una inferencia razonable (también bloquea la fila en el UPDATE), no verificada. Tests con Testcontainers. |
| `GET /api/pedidos/{id}` | La cabecera `Location` ya apunta a él; no lo pedía el enunciado. |
| Cancelación de pedidos | Estado `CANCELADO`; no hay endpoint que lo use. |
| HTTPS hacia el proveedor | Obligatorio en un entorno real (el token viaja en una cabecera); en local se usa `http` con WireMock. |
| Importe total en el límite | Si `importeProductos` está cerca del máximo de `NUMERIC(19,2)`, sumar el envío podría desbordar `importe_total`. Caso extremo no controlado. |
| Reglas de arquitectura automáticas | Test con ArchUnit para las dependencias entre paquetes (hoy se comprueban a mano). |
| Observabilidad | Actuator con métricas de Resilience4j y del pool, sin exponer `/env` ni `/configprops`. |

## Uso de IA

He usado Claude Code como asistente de diseño, implementación y revisión. El detalle, redactado por mí, está en [`docs/registro-ia.md`](docs/registro-ia.md). En resumen:

- **Diseño:** la IA propuso varias cosas que corregí o descarté: recuperar reservas por antigüedad, confiar en `@Version` con un UPDATE masivo, 3 intentos con un peor caso de "~7 s", un único puerto con los casos de uso usando Spring Data directamente y una relación JPA `Pedido → Cliente`.
- **Implementación:** el asistente principal escribió el código paso a paso, con mi confirmación en cada paso. Agentes de revisión de solo lectura (`java-reviewer`, `resilience-reviewer`, `security-reviewer`) revisaron los pasos 2 y 3. Antes de aplicar nada, cada hallazgo se contrastó con el código y se reprodujo cuando era posible. Hallazgos aceptados:
  - Una cantidad con decimales (`1.9`) se truncaba a `1` sin avisar.
  - `@Digits` no ignora los ceros finales de un `BigDecimal`, al contrario de lo que había documentado.
  - En Spring 6.2, el "timeout de lectura" del cliente del JDK cubre todo el intercambio, incluida la conexión.
  - Un corte del cuerpo de la respuesta se clasificaba como 502 en lugar de 503.
  - Un token con salto de línea final aparecía completo en el mensaje de una excepción.
- **Tests:** la IA propuso tests que comprobé para asegurarme de que prueban algo. Por ejemplo, con la condición `estado = 'CREADO'` eliminada del UPDATE, los tests de concurrencia fallan.

La configuración de los agentes está descrita en [`docs/orquestacion.md`](docs/orquestacion.md).
