#ifndef RingLogVersion
  #error RingLogVersion must be supplied by BuildWindowsInstaller.ps1
#endif
#ifndef RingLogArchitecture
  #error RingLogArchitecture must be x64 or x86
#endif
#ifndef RingLogStageDir
  #error RingLogStageDir must point to the verified staging directory
#endif
#ifndef RingLogOutputDir
  #error RingLogOutputDir must point to the installer output directory
#endif
#ifndef RingLogIconFile
  #error RingLogIconFile must point to the verified RingLog icon
#endif

[Setup]
AppId={{CD4DE8C8-42D5-4A9C-B792-CA3D29616163}
AppName=RingLog
AppVersion={#RingLogVersion}
AppVerName=RingLog {#RingLogVersion}
VersionInfoVersion={#RingLogVersion}
VersionInfoCompany=RingLog
VersionInfoDescription=RingLog - Diario de campo
VersionInfoProductName=RingLog
DefaultDirName={localappdata}\Programs\RingLog
DefaultGroupName=RingLog
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
MinVersion=6.2
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
OutputDir={#RingLogOutputDir}
OutputBaseFilename=RingLog-Setup-{#RingLogArchitecture}
UninstallDisplayName=RingLog
UninstallDisplayIcon={app}\RingLog.ico
SetupIconFile={#RingLogIconFile}
CloseApplications=no
RestartApplications=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
#if RingLogArchitecture == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#elif RingLogArchitecture == "x86"
ArchitecturesAllowed=x86compatible and not x64compatible
#else
  #error RingLogArchitecture must be x64 or x86
#endif

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos adicionales:"; Flags: unchecked

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[InstallDelete]
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\libs"
Type: files; Name: "{app}\RingLog.jar"
Type: files; Name: "{app}\RingLog.ico"

[Files]
Source: "{#RingLogStageDir}\RingLog.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RingLogStageDir}\distribution.properties"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RingLogStageDir}\libs\*"; DestDir: "{app}\libs"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#RingLogStageDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#RingLogStageDir}\RingLog.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\RingLog"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\RingLog.jar"""; WorkingDir: "{app}"; IconFilename: "{app}\RingLog.ico"
Name: "{autodesktop}\RingLog"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\RingLog.jar"""; WorkingDir: "{app}"; IconFilename: "{app}\RingLog.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\RingLog.jar"""; WorkingDir: "{app}"; Description: "Abrir RingLog"; Flags: nowait postinstall

[Code]
const
  SynchronizeAccess = $00100000;
  WaitTimeout = 258;

function OpenProcess(DesiredAccess: Cardinal; InheritHandle: Boolean; ProcessId: Cardinal): THandle;
  external 'OpenProcess@kernel32.dll stdcall';
function WaitForSingleObject(Handle: THandle; Milliseconds: Cardinal): Cardinal;
  external 'WaitForSingleObject@kernel32.dll stdcall';
function CloseHandle(Handle: THandle): Boolean;
  external 'CloseHandle@kernel32.dll stdcall';

function WaitForRingLogToClose: Boolean;
var
  ProcessId: Integer;
  ProcessHandle: THandle;
  WaitResult: Cardinal;
begin
  Result := True;
  if ExpandConstant('{param:RINGLOGUPDATE|0}') <> '1' then
    Exit;

  ProcessId := StrToIntDef(ExpandConstant('{param:RINGLOGPID|0}'), 0);
  if ProcessId <= 0 then
  begin
    MsgBox('No se pudo identificar la instancia de RingLog que debe cerrarse.', mbError, MB_OK);
    Result := False;
    Exit;
  end;

  ProcessHandle := OpenProcess(SynchronizeAccess, False, ProcessId);
  if ProcessHandle = 0 then
    Exit;
  try
    WaitResult := WaitForSingleObject(ProcessHandle, 30000);
    if WaitResult = WaitTimeout then
    begin
      MsgBox('RingLog no pudo cerrarse a tiempo. La actualización se ha cancelado sin modificar la instalación.', mbError, MB_OK);
      Result := False;
    end;
  finally
    CloseHandle(ProcessHandle);
  end;
end;

function InitializeSetup: Boolean;
begin
  Result := WaitForRingLogToClose;
end;
