.DEFAULT_GOAL := help

POWERSHELL ?= powershell.exe
MAVEN ?= mvn.cmd

JAVA_HOME_DIR := $(CURDIR)/.deps/jdk17/zulu17.42.19-ca-jdk17.0.7-win_x64
ISCC := $(CURDIR)/.deps/inno-6.7.3/ISCC.exe
RUNTIME_X64 := $(CURDIR)/vendor-runtimes/zulu17.42.19-ca-jre17.0.7-win_x64.zip
RUNTIME_X86 := $(CURDIR)/vendor-runtimes/zulu17.42.19-ca-jre17.0.7-win_i686.zip
INSTALLER_X64 := $(CURDIR)/target/windows-installer/output/x64/RingLog-Setup-x64.exe
INSTALLER_X86 := $(CURDIR)/target/windows-installer/output/x86/RingLog-Setup-x86.exe

.PHONY: help test package verify installer-x64 installer-x86 installers hashes clean

help:
	@echo RingLog - comandos disponibles
	@echo   make test            Ejecuta toda la suite con Java 17
	@echo   make package         Ejecuta tests y genera el JAR
	@echo   make verify          Valida el JAR y su arranque aislado
	@echo   make installer-x64   Genera el instalador Windows x64
	@echo   make installer-x86   Genera el instalador Windows x86
	@echo   make installers      Genera ambos instaladores
	@echo   make hashes          Muestra tamano y SHA-256 de los instaladores
	@echo   make clean           Elimina los artefactos de target con Maven
	@echo Si Maven no esta en PATH: make installer-x64 MAVEN=C:/ruta/mvn.cmd

test:
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(MAVEN)' clean test; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }"

package:
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(MAVEN)' clean package; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }"

verify: package
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(JAVA_HOME_DIR)/bin/java.exe' scripts/VerifyDistribution.java; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }; & '$(JAVA_HOME_DIR)/bin/java.exe' scripts/VerifyDistribution.java --startup-only; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }"

installer-x64: verify
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(CURDIR)/scripts/BuildWindowsInstaller.ps1' -Architecture x64 -RuntimeZip '$(RUNTIME_X64)' -IsccPath '$(ISCC)'; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }; Write-Host 'Instalador generado: $(INSTALLER_X64)'"

installer-x86: verify
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(CURDIR)/scripts/BuildWindowsInstaller.ps1' -Architecture x86 -RuntimeZip '$(RUNTIME_X86)' -IsccPath '$(ISCC)'; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }; Write-Host 'Instalador generado: $(INSTALLER_X86)'"

installers: installer-x64 installer-x86
	@echo Ambos instaladores se han generado correctamente.

hashes:
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$files=@('$(INSTALLER_X64)','$(INSTALLER_X86)'); foreach ($$file in $$files) { if (-not (Test-Path -LiteralPath $$file -PathType Leaf)) { throw 'Falta el instalador: ' + $$file }; $$item=Get-Item -LiteralPath $$file; $$sha=[System.Security.Cryptography.SHA256]::Create(); $$stream=[System.IO.File]::OpenRead($$file); try { $$hash=([System.BitConverter]::ToString($$sha.ComputeHash($$stream))).Replace('-','').ToLowerInvariant() } finally { $$stream.Dispose(); $$sha.Dispose() }; Write-Host ($$item.Name + '  ' + $$item.Length + ' bytes  SHA-256 ' + $$hash) }"

clean:
	@$(POWERSHELL) -NoProfile -ExecutionPolicy Bypass -Command "$$ErrorActionPreference='Stop'; $$env:JAVA_HOME='$(JAVA_HOME_DIR)'; $$env:Path='$(JAVA_HOME_DIR)/bin;' + $$env:Path; & '$(MAVEN)' clean; if ($$LASTEXITCODE -ne 0) { exit $$LASTEXITCODE }"
