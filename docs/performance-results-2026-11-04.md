# Resultados de rendimiento — 4 de noviembre de 2026

Las exportaciones y los PDF se optimizaron después de esta primera batería. La continuación
con los cambios y las comprobaciones está en
[Optimización de exportaciones y PDF](export-pdf-performance-2026-11-04.md).

Las pruebas terminaron con **613 resultados PASS**, sin procesos incompletos ni casos
omitidos. Varios resultados separan fases de una misma operación; no representan 613
funciones distintas. La suite funcional completa también pasó: 68 clases de pruebas y
`RingLogTestSuite: PASS`. El paquete final se compiló y se ejecutó extraído del ZIP,
utilizando únicamente el JRE de RingLog, sin Maven.

## Condiciones

Equipo de medición: Windows 11 amd64, Java Azul 17.0.7 de 64 bits, G1 y 12 procesadores
lógicos. El heap máximo fue de 512 MiB, salvo la prueba de carga de 256 MiB. Esto limita
la memoria de objetos Java, no toda la memoria del proceso.

Todos los datos son sintéticos y están aislados del diario instalado. Cada grupo y tamaño
usa una JVM y una base SQLite independientes, con el esquema actual, catálogos, historiales,
notas cortas y largas y 24 fotos JPEG de 2400 × 1800. La preparación queda fuera de los casos.
La batería general usa diez eventos por ave, dos calentamientos y cinco muestras; las
operaciones pesadas de archivos usan cero calentamientos y tres muestras. La ventana real
usa hasta tres muestras sin calentamiento. p95 con estas cantidades es orientativo.

| Campaña | Datos | Resultados PASS |
|---|---|---:|
| Consultas, escrituras, interfaz y archivos | 1.000 y 10.000 registros, 512 MiB | 300 |
| Carga de base de datos e interfaz | 50.000 registros, 256 MiB | 135 |
| Ventana real, calendario y copia portátil | 1.000 registros | 76 |
| Validación del ZIP extraído, sin herramientas de desarrollo | 100 registros | 102 |

La pintura del grupo `ui` se hace en memoria. El grupo `desktop` abre una ventana real,
pinta con `paintImmediately` y sincroniza el toolkit; tampoco mide la latencia física del
ratón ni el tiempo de lanzamiento del ejecutable nativo. Las copias se hicieron en disco
local, no en un USB. No se midieron servicios de red ni el instalador.

## Mejora SQL aplicada y comprobada

La carga del archivo completo repetía una subconsulta del historial por cada registro.
Ahora calcula los tipos del historial una vez por ave y los une al listado. Las consultas
de recientes y de una sola ave mantienen su recorrido por índices.

Comparación del repositorio real, mismo entorno y carga, cien eventos por ave, dos
calentamientos y cinco muestras en cada versión:

| Registros | Mediana anterior | Mediana optimizada | Reducción observada |
|---:|---:|---:|---:|
| 1.000 | 41,71 ms | 20,78 ms | 50,2 % |
| 10.000 | 293,18 ms | 115,19 ms | 60,7 % |

La regresión funcional comprueba igualdad de registros, orden, historial y fotos frente
a las consultas de recientes y de un ave. Los benchmarks también contrastan conteos y
checksums. Estos porcentajes corresponden a esa carga concreta, no a toda la aplicación.

## Tiempos representativos después de los cambios

| Operación | Datos | Mediana | p95 |
|---|---|---:|---:|
| Consulta del archivo completo | 10.000 registros, diez eventos por ave | 142,05 ms | 168,76 ms |
| Listado listo y pintado en memoria | 10.000 registros | 301,76 ms | 307,55 ms |
| Cambiar rango de meses: acción en el hilo de interfaz | 10.000 registros | 0,53 ms | 0,71 ms |
| Cambiar rango de meses: acción hasta resultado pintado | 10.000 registros | 193,36 ms | 197,97 ms |
| Listado listo y pintado en memoria | 50.000 registros, 256 MiB | 897,19 ms | 1.567,72 ms |
| Ventana visible con los primeros datos | 1.000 registros | 300,04 ms | 1.296,79 ms |
| Abrir y pintar el calendario visible | Ventana real, 1.000 registros | 43,07 ms | 62,21 ms |
| Cambiar de mes y pintar el calendario visible | Ventana real, 1.000 registros | 39,39 ms | 41,43 ms |
| PDF, incluida validación | 250 registros | 4.623,16 ms | 5.489,09 ms |
| Exportación nativa completa | 10.000 registros | 9.227,48 ms | 9.791,56 ms |
| Backup v3 completo | 10.000 registros | 10.354,95 ms | 11.531,76 ms |
| Restauración nativa desde plan analizado | 10.000 registros | 2.324,21 ms | 2.402,15 ms |
| Snapshot SQLite | 10.000 registros | 37,80 ms | 50,97 ms |

Los filtros incluyen una espera programada de 140 ms antes del cálculo. El tiempo hasta
resultado incluye esa espera, trabajo asíncrono y pintura; el tiempo del manejador permite
distinguirlo del bloqueo de la interfaz. Con 50.000 registros, los manejadores de los rangos
de día, meses y años quedaron por debajo de 0,8 ms de mediana.

La copia portátil completa se creó y validó correctamente en una muestra de 3.805,63 ms,
incluyendo aplicación, bibliotecas, JRE y datos sintéticos. Una muestra no permite estimar
variabilidad ni rendimiento de un dispositivo externo.

## Siguientes mediciones y mejoras candidatas

1. Repetir el paquete en el portátil Windows 8.1, con cargador y condiciones constantes.
   Estos tiempos de Windows 11 no demuestran el rendimiento de su CPU, disco o sistema gráfico.
2. Perfilar las exportaciones completas: son las operaciones de archivos más lentas de esta
   batería. Separar lectura, construcción del libro y compresión permitirá elegir la mejora.
3. Separar generación y validación del PDF. La validación reabre el documento y extrae todo
   su texto. El generador también repite comprobaciones de caracteres y anchuras; una caché
   limitada a cada exportación es candidata. Hace falta profiling antes de atribuirles el coste
   y conservar las comprobaciones de integridad y el tratamiento de Unicode al optimizar.
4. Si el portátil tiene archivos grandes, medir la carga inicial del listado: a 50.000 registros
   todavía requiere casi un segundo en este equipo. La carga de la primera página y la
   preparación del resto por separado son candidatas, sujetas a esas mediciones.

## Repetir y conservar resultados

La guía reproducible está en `docs/benchmarks.md`. El artefacto es
`target/RingLog-Benchmarks.zip`; al descomprimir y abrir `Ejecutar-benchmark.cmd` se ejecutan
1.000 registros e incluye la ventana real y el calendario. No necesita Internet ni Maven,
y utiliza el Java de la instalación de RingLog. No se suben resultados automáticamente.

Los datos originales de esta sesión están en `target/benchmarks/final`, `stress-256m`,
`desktop-portable` y `kit-final`, con JSON, CSV, informes Markdown y logs. La comparación SQL
está en `target/benchmarks/database-comparison.csv`, y sus campañas en `baseline-db` y
`optimized-db`. Los JSON guardan muestras originales, entorno, GC, memoria observada y
huellas de código; las campañas finales también identifican clases compiladas y dependencias.
`target` es una salida de build: conviene conservar los informes antes de limpiar esa carpeta.
