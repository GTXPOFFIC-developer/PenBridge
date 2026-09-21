; Script generated for Inno Setup 6
; Dashboard Windows Host Installer

#define MyAppName "Dashboard Host"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Dashboard"
#define MyAppExeName "DashboardHost.exe"

[Setup]
; NOTE: The value of AppId uniquely identifies this application.
AppId={{5E97D4B6-7C12-4EA4-A651-38A201A94002}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Dashboard Host
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\bin\Installer
OutputBaseFilename=Dashboard-Setup
Compression=lzma2/max
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startwithwindows"; Description: "Start Dashboard Host automatically when Windows starts"; GroupDescription: "Startup:"

[Files]
Source: "..\bin\Release\net8.0-windows\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{autostartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: startwithwindows

[Run]
; Configure Windows Defender Firewall rules for UDP discovery, UDP data, and TCP USB
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Discovery (UDP 41173)"" dir=in action=allow protocol=UDP localport=41173 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Data (UDP 41174)"" dir=in action=allow protocol=UDP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host USB (TCP 41174)"" dir=in action=allow protocol=TCP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Remove Firewall rules on uninstallation
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Discovery (UDP 41173)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Data (UDP 41174)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host USB (TCP 41174)"""; Flags: runhidden
