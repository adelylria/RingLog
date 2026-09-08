# RingLog

RingLog es una aplicación de escritorio para conservar un diario de anillamiento de aves.
Organiza registros, especies, lugares, fotografías y revisiones con una interfaz pensada para
consultar el archivo como un diario, no como una tabla.

## Funciones principales

- Creación, consulta y edición de registros de anillamiento, control y recuperación.
- Catálogos reutilizables de aves, especies y lugares.
- Filtros por texto, tipo, especie, lugar y fecha.
- Informes de los registros filtrados en XLSX y PDF.
- Importación del formato normalizado RingLog Legacy Migrator v5/v5.2.
- Backups y exportaciones nativas restaurables, incluyendo fotografías mediante ZIP.
- Revisión integrada de conflictos conservando toda la trazabilidad de la migración.
- Tema claro y oscuro persistente.
- Copia portátil de solo lectura para consultar y exportar informes sin modificar los datos.

## Compatibilidad

La aplicación se compila con Java 17. El instalador para Windows incluye su propio runtime, por
lo que el usuario no necesita instalar Java.

- Windows 8.1 x64: validado en una VM limpia.
- Windows 8: pendiente de validación.
- x86: infraestructura de empaquetado disponible, pendiente de validación física.

## Compilar y probar

Requisitos:

- JDK 17 real.
- Maven 3.6.3 o posterior.

```powershell
mvn clean test
mvn clean package
java scripts/VerifyDistribution.java
```

El JAR y sus dependencias quedan en:

```text
target/RingLog-<version>.jar
target/libs/
```

Los tests que utilizan el XLSX v5.2 real son opcionales porque ese dataset no forma parte del
repositorio público. Para ejecutarlos, define:

```powershell
$env:RINGLOG_LEGACY_V52_FIXTURE = "C:\ruta\segura\ringlog-import.xlsx"
mvn clean test
```

Si existe un proyecto hermano `RingLogLegacyMigrator`, los tests también buscan por defecto
`../RingLogLegacyMigrator/output/ringlog-import.xlsx`.

## Instaladores de Windows

La configuración bloquea Inno Setup 6.7.3 y los runtimes Zulu 17 mediante nombre y SHA-256.
Los binarios externos se mantienen fuera de Git.

```powershell
make installer-x64
make installer-x86
make hashes
```

Consulta [la guía de distribución](docs/windows-distribution.md) para preparar instaladores y
releases firmadas.

## Datos del usuario

RingLog separa los datos de la instalación:

```text
%LOCALAPPDATA%\RingLog\
  data/
  photos/
  unassigned-photos/
  backups/
  logs/

%APPDATA%\RingLog\
  preferences.properties
```

La desinstalación no elimina estos directorios. La base de datos, fotografías, backups, logs,
runtimes, artefactos de build y claves privadas están excluidos expresamente por
[.gitignore](.gitignore).

El esquema SQL versionado se conserva en
[`database/schema.sql`](database/schema.sql); una instalación nueva crea directamente el schema
actual y las instalaciones existentes se actualizan de forma incremental y segura.
