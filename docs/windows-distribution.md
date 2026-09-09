# Distribución de RingLog para Windows

RingLog se distribuye como instalador por usuario, con un JRE Zulu 17 incluido. La arquitectura
se elige explícitamente al preparar cada instalador; el proyecto no presupone si el equipo final
es x86 o x64.

## Entradas bloqueadas

- JDK de compilación: Java 17 real.
- Inno Setup: exactamente 6.7.3.
- Runtime: el nombre y SHA-256 de cada ZIP están en
  `distribution/runtime-lock.properties`.
- Versión: únicamente la versión de `pom.xml`.

Los ZIP oficiales se guardan localmente, por ejemplo bajo `vendor-runtimes/`, que está ignorado
por Git. Ningún script descarga runtimes ni herramientas automáticamente.

## Preparar un instalador

1. Cambiar `pom.xml` de `MAJOR.MINOR.PATCH-SNAPSHOT` a `MAJOR.MINOR.PATCH`.
2. Ejecutar con un JDK 17 real:

   ```powershell
   mvn clean test
   mvn clean package
   java scripts/VerifyDistribution.java
   ```

3. Compilar solo la arquitectura confirmada del equipo:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts/BuildWindowsInstaller.ps1 `
     -Architecture x64 `
     -RuntimeZip vendor-runtimes/zulu17.42.19-ca-jre17.0.7-win_x64.zip `
     -IsccPath "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
   ```

   Para x86 se cambian `-Architecture` y el ZIP por las variantes x86. El script rechaza una
   versión distinta de Inno Setup 6.7.3, un runtime con nombre/hash distinto, una versión Maven
   `-SNAPSHOT` y cualquier arquitectura distinta de x86/x64.

El resultado se escribe en `target/windows-installer/output/<arquitectura>/`. El instalador no
lee, migra ni elimina `%LOCALAPPDATA%\RingLog` ni `%APPDATA%\RingLog`.

## Claves de actualización

La pareja se genera una sola vez:

```powershell
java scripts/GenerateUpdateSigningKeys.java `
  .release-secrets/update-signing-private.pk8 `
  src/main/resources/ringlog-update-public-key.der
```

La clave pública forma parte de RingLog. La clave privada de `.release-secrets/` está ignorada y
debe copiarse inmediatamente a dos almacenamientos offline seguros. No se sube a GitHub, no se
incluye en el instalador y no se envía junto con una release.

## Publicación manual segura

La release pública debe tener exactamente el mismo SemVer que `pom.xml` y el tag
`vMAJOR.MINOR.PATCH`.

1. Preparar y probar el instalador de la arquitectura elegida en una VM limpia.
2. Crear el manifest. La herramienta calcula por sí misma tamaño y SHA-256 del instalador:

   ```powershell
   java scripts/CreateUpdateManifest.java `
     --repository adelylria/RingLog `
     --installer x64=target/windows-installer/output/x64/RingLog-Setup-x64.exe `
     --output target/release/update-manifest.json
   ```

3. Firmar los bytes exactos del manifest. La herramienta comprueba que ambas claves coinciden
   antes de escribir la firma:

   ```powershell
   java scripts/SignUpdateManifest.java `
     .release-secrets/update-signing-private.pk8 `
     src/main/resources/ringlog-update-public-key.der `
     target/release/update-manifest.json `
     target/release/update-manifest.json.sig
   ```

4. Crear manualmente en GitHub una release **draft** para el tag correspondiente.
5. Subir exactamente:
   - `RingLog-Setup-x64.exe` o `RingLog-Setup-x86.exe`;
   - `update-manifest.json`;
   - `update-manifest.json.sig`.
6. Descargar de nuevo los tres assets del draft y comprobar nombres, tamaño, SHA-256 y firma.
7. Comprobar que el manifest contiene la URL de descarga versionada correcta.
8. Publicar la release **en último lugar**.

Una release pública expone automáticamente las URLs estables:

- `https://github.com/adelylria/RingLog/releases/latest/download/update-manifest.json`
- `https://github.com/adelylria/RingLog/releases/latest/download/update-manifest.json.sig`

El build público debe filtrar esa base con:

```powershell
mvn -Dringlog.update.baseUrl=https://github.com/adelylria/RingLog/releases/latest/download clean package
```

En desarrollo, si la base no se define, las actualizaciones permanecen desactivadas.

## Comportamiento del actualizador

RingLog comprueba en segundo plano como máximo una vez cada 24 horas. También ofrece “Buscar
actualizaciones” en Ajustes. Descarga manifest y firma, verifica Ed25519 antes de parsear, compara
SemVer, elige la arquitectura de la JVM y descarga únicamente el instalador indicado. Durante la
descarga valida HTTPS, tamaño y SHA-256. Si todo coincide, inicia el instalador con `/SILENT`, le
pasa el PID actual, cierra RingLog ordenadamente y el instalador espera su salida antes de cambiar
archivos. Después vuelve a abrir RingLog con el `javaw.exe` incluido.

Errores de red, releases incompletas, firma inválida, arquitectura ausente o hash incorrecto no
bloquean RingLog ni modifican la instalación existente.

## Validaciones físicas

El usuario confirmó el 8 de septiembre de 2026 el funcionamiento de RingLog en una VM limpia
Windows 8.1 x64. Aún deben probarse en el portátil real el instalador, la actualización y la
desinstalación, y queda pendiente la VM de Windows 8. Si Windows 8 falla, `MinVersion` se elevará
de 6.2 a 6.3.
