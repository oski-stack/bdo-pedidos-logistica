# Registro de uso de IA

> Apunta solo cambios que hayas revisado y entendido. Redacta cada entrada con tus palabras: en la revisión te preguntarán por ellas.

| Fase | Qué propuso la IA | Qué cambié y por qué |
|---|---|---|
| Diseño | Recuperar automáticamente reservas en `CONFIRMANDO` de más de 30 s | Lo descarté: un proceso pausado puede seguir vivo y provocar una doble llamada o sobrescribir el resultado. Queda documentado como fuera de alcance (D4). |
| Diseño | "`@Version` actúa como red de seguridad" junto a un UPDATE condicional | Una revisión cruzada detectó que un UPDATE masivo no pasa por el bloqueo optimista de JPA. Ahora la reserva incrementa la versión explícitamente y la finalización recarga la entidad (D4). |
| Diseño | 3 intentos y un peor caso de "~7 s" | Era incorrecto: un 503 también puede tardar. Reduje a 2 intentos y documento los timeouts por intento, sin prometer un límite global (D6). |
| Diseño | Descartar el bloqueo optimista como alternativa | Era una objeción demasiado general: reservar con `@Version` en una transacción corta, con commit antes de llamar, también es válido. Lo documento como alternativa (D4). |
| Diseño | Arquitectura hexagonal con un único puerto (`ProveedorTarifas`), con los casos de uso usando Spring Data directamente | La aplicación habría dependido de infraestructura. Añadí `PedidoRepositorio` y `ClienteRepositorio` como puertos y agrupé todos los puertos en `application/port/out` (D2). |
| Diseño | Relación JPA `Pedido → Cliente` | Guardo `clienteId`: es una decisión de alcance, porque basta con validar que el cliente existe (D2). |
| Implementación | … | … |
