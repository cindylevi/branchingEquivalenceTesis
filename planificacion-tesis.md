# Planificación de la tesis

Notas de planificación para la tesis de Licenciatura: *"Escalando la síntesis composicional de controladores mediante una nueva técnica de minimización para branching bisimilarity"*.

## Contexto

- **Director:** Sebastian Uchitel
- **Codirector:** Hernán Gabriel Gagliardi
- **Aporte central:** reemplazar el algoritmo de Weak Synthesis Observational Equivalence (WSOE) por el algoritmo de branching bisimilarity de Groote–Jansen–Keiren–Wijs (2019) como técnica de minimización en el flujo composicional de síntesis de controladores.

### Papers de referencia

1. **Algoritmo eficiente de branching bisimilarity** — Groote et al., 2019. <https://arxiv.org/abs/1909.10824>
2. **Noción de equivalencia (WSOE) y algoritmo de minimización existente** — Mohajerani / Malik / Fabian (Chalmers). <https://publications.lib.chalmers.se/records/fulltext/165507/local_165507.pdf>
3. **Paper composicional propio que usa (2) y donde queremos llevar las ideas de (1)** — <https://arxiv.org/abs/2506.16557>

## Estructura propuesta de la tesis

Tomada como molde la tesis de ejemplo previa (exploración on-the-fly de RA) que ya está en `capitulos/`, se reusa el esqueleto de capítulos pero con el contenido propio.

### Cap. 1 — Introducción

- Síntesis automática de controladores y motivación.
- Composicionalidad: por qué se necesita minimizar entre composiciones.
- Cuello de botella: WSOE en el paper composicional.
- Propuesta: reemplazar WSOE por branching bisimilarity con el algoritmo eficiente de Groote et al.
- Aportes y guía de lectura.

### Cap. 2 — Antecedentes

- 2.1 LTS, autómatas y composición paralela (reusable casi tal cual del cap. 2 ejemplo).
- 2.2 Problema de control: safe y non-blocking.
- 2.3 Síntesis composicional (Mohajerani–Malik–Fabian, Chalmers): la noción de equivalencia que usa el paper composicional.
- 2.4 WSOE: definición, algoritmo de minimización, complejidad. Por qué escala mal.

### Cap. 3 — Branching bisimilarity

- 3.1 Definición formal.
- 3.2 Algoritmo O(m log n) de Groote et al.: ideas principales, estructuras (splitters, bloques), invariantes.

### Cap. 4 — Equivalencia entre WSOE y branching bisimilarity (capítulo teórico)

Ver discusión en sección "Decisiones tomadas" más abajo.

- 4.1 Recordatorio de WSOE (referencia al cap. 2).
- 4.2 Diferencias intuitivas y casos motivadores.
- 4.3 Teorema de equivalencia + demostración.
- 4.4 Corolarios: qué se preserva en el contexto composicional (control, safe, non-blocking).

### Cap. 5 — Adaptación e implementación

- 5.1 Cómo encaja branching bisimilarity en el flujo composicional.
- 5.2 Decisiones de diseño y diferencias respecto del paper original.
- 5.3 Detalles de implementación.
- 5.4 Validación de correctitud.

### Cap. 6 — Experimentación y resultados

- 6.1 Casos de estudio / benchmark (TravelAgency u otros del paper composicional).
- 6.2 Métricas: tiempo, memoria, tamaño de LTS minimizados.
- 6.3 Comparación WSOE vs branching bisimilarity.
- 6.4 Discusión: dónde escala, dónde no, casos límite.

### Cap. 7 — Conclusiones

- Aportes, limitaciones, trabajo futuro.

### Apéndice

- Pseudocódigo extendido, pruebas formales, tablas completas de resultados.

## Decisiones tomadas

### Dónde va la demo teórica WSOE ≡ branching bisimilarity

**Decisión: capítulo propio (Cap. 4).** Razones:

1. Es el aporte teórico central. Sin esa demo no se puede sustituir WSOE; darle capítulo propio lo señaliza.
2. La demo va a necesitar lemas auxiliares (preservación bajo composición paralela, preservación de la noción de "ganador") que ensucian el cap. de branching si se meten ahí.
3. El lector que solo quiere los resultados experimentales puede saltearlo; el que quiere el rigor teórico lo encuentra concentrado.

Alternativa descartada: meterlo como sección 3.3. Solo conviene si la demo termina siendo corta (3-6 páginas).

### Por dónde arrancar a escribir

**Recomendación: arrancar por Cap. 5 (Implementación).** Razones:

- Es descriptivo, no creativo: contás qué decisiones tomaste y por qué.
- Te obliga a inventariar qué hiciste, lo que muestra qué definiciones vas a necesitar después en antecedentes (no al revés).
- Bajo riesgo de bloqueo: si te trabás, igual avanzás.
- Mientras escribís, anotás "esto necesita definir X en cap. 2" en una lista paralela.

Alternativa: arrancar por Cap. 4 (demo teórica) si la prueba ya está madura en notas. Ventaja: lo más exigente mentalmente, mejor hacerlo fresca y al principio. Riesgo: si la demo tiene huecos, te trabás.

**Lo que no conviene mezclar:** experimentación sin tener implementación escrita. Los resultados se entienden mucho peor sin el contexto del Cap. 5.

## Cómo arrancar el Cap. 5 (Implementación) — cuestionario para brain dump

Responder en bullets, sin prosa. Cada bullet termina siendo un párrafo del capítulo.

### Contexto / dónde encaja

- ¿Sobre qué herramienta se trabajó? (¿MTSA? ¿extensión propia?)
    MTSA
- ¿Qué módulo / clase se reemplazó o agregó? Ruta concreta.
    EN mtstools, en DCS / Compositional, se creo un archivo BranchingEquivalence.java con el main del algoritmo
- ¿Dónde en el flujo composicional se llama el código nuevo? ¿Qué llamada a WSOE quedó reemplazada?
    En el flujo composicional, el arhcivo con la logica principal se encuentra en DCS/Compositional/CompositionalApproach.java . ahi, se hacia el preprocesamiento necesario para WSOE y se hacia la llamada al algoritmo. MTS<Long, String> minimizationResult
                    = syntEq.minimiseWithPartition(partition, localControllerMTS, localAlphabet, translatedControllableLabels, translatorControllable); fue reemplazado por BranchingEquivalence.buildMinimisedMTSFromPartition(localControllerMTS, localAlphabet, translatorControllable, branchingPartition)

### Qué se construyó

- ¿Se implementó el algoritmo de Groote et al. tal cual, o una variante?
    se intento implementar el algoritmo de groote et al tal cual, pero se fueron haciendo iteraciones de las implementaciones surgidas. En la primer iteracion, nos enfocamos en hacer que el algoritmo sea funcional nada mas. 
- ¿Qué estructuras de datos clave se usaron? (partición refinable, lista de splitters, bloques, etc.)
- ¿Cuál es el input y el output del módulo? (LTS de entrada → LTS minimizado / partición de equivalencia)

### Decisiones de diseño no triviales

- ¿Qué decisión se tomó que no estaba dictada por el paper? (ej: cómo manejar τ-loops, cómo representar bloques, qué hacer con marcado / no-marcado, controlables / no-controlables)
- ¿Por qué esa decisión y no otra? (una línea por decisión)

### Diferencias con el paper original

- El paper de Groote asume LTS "puros". El contexto composicional tiene LTS con eventos controlables / no-controlables y estados marcados. ¿Cómo se adaptó?
- ¿Hubo casos del paper composicional que el algoritmo original no cubría?

### Validación

- ¿Cómo se verificó que la implementación es correcta? (tests, comparación con WSOE en casos chicos, propiedades invariantes)
- ¿Hay tests escritos? ¿Cuántos casos?

### Próximo paso

Después de responder estas preguntas (aunque sea desordenado):

1. Cada bullet → un párrafo (o medio).
2. Agrupar en 4 secciones: **Contexto y arquitectura**, **El algoritmo implementado**, **Adaptaciones al contexto composicional**, **Validación**.
3. Recién ahí escribir prosa.
