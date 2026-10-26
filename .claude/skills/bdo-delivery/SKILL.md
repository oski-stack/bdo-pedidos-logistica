---
name: bdo-delivery
description: Coordina por fases el análisis, implementación, validación y revisión independiente de la prueba técnica de pedidos con integración logística.
---

# Flujo de trabajo de la prueba BDO

Tarea: **$ARGUMENTS**

Actúa como orquestador principal. La implementación la realiza el asistente principal; los agentes especialistas analizan o validan de forma independiente y no editan archivos.
Respeta `CLAUDE.md` y `docs/decisiones.md`. Trabaja un paso del plan cada vez y no avances al siguiente sin confirmación del usuario.

## Fase 1 — Entender y acotar
- Resume el requisito, el comportamiento esperado y el paso del plan afectado.
- Consulta `pedido-architect` cuando haya cambios de diseño, dominio, contratos o transacciones.
- Separa hechos del enunciado, supuestos y decisiones pendientes.
- Propón los archivos que probablemente cambiarán y el criterio de aceptación.

## Fase 2 — Implementar
- Antes de editar, explica en 3–5 líneas el cambio previsto.
- Implementa el cambio mínimo en el asistente principal, respetando los puertos y adaptadores.
- No delegues la escritura de producción a un agente revisor.
- No hagas commit ni push.

## Fase 3 — Validar
- Usa `test-validator` para ejecutar la validación apropiada y reportar evidencia real.
- Si el cambio afecta a integración externa, reintentos o concurrencia, consulta `resilience-reviewer`.
- Si toca token, validación o exposición de datos, consulta `security-reviewer`.
- Si añade o modifica Java/Spring, consulta `java-reviewer` después de los tests.
- Puedes ejecutar revisiones independientes en paralelo cuando no dependan unas de otras; no dupliques agentes para el mismo riesgo.

## Fase 4 — Integrar y cerrar
- Integra resultados, elimina duplicados y comprueba cada hallazgo contra el código.
- Corrige solo defectos relevantes para el alcance; vuelve a validar lo que cambiaste.
- Actualiza `docs/registro-ia.md` con lo que realmente se ejecutó, qué encontró y qué decisión tomó el autor. No registres agentes que solo estaban configurados.
- Resume archivos cambiados, comportamiento, comandos y resultados, hallazgos aceptados/descartados y riesgos no validados.
- Propón un mensaje de commit en español; deja que el usuario haga el commit.

## Criterio de orquestación
Usa agentes cuando aporten una perspectiva independiente y concreta. No hace falta invocar todos en cada cambio. Para la entrega final, prioriza una revisión de arquitectura, una de resiliencia/concurrencia y la validación de tests; añade seguridad si se modificó manejo de secretos o exposición de datos.
