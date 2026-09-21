; ================================================================
; Dashboard Host — x64 Installer (DashboardX64.exe)
; ================================================================

#define MyAppName     "Dashboard Host"
#define MyAppVersion  "1.0.0"
#define MyAppPublisher "Dashboard"
#define MyAppExeName  "DashboardHost.exe"
#define MyArch        "x64"

[Setup]
AppId={{5E97D4B6-7C12-4EA4-A651-38A201A94002}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion} ({#MyArch})
DefaultDirName={autopf}\Dashboard Host
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\bin\Installer
OutputBaseFilename=DashboardX64
Compression=lzma2/max
SolidCompression=yes
; Require 64-bit Windows — installer will refuse on 32-bit systems
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
WizardStyle=modern
WizardImageStretch=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon";       Description: "{cm:CreateDesktopIcon}";       GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startwithwindows";  Description: "Start Dashboard Host automatically when Windows starts"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
; 64-bit publish output
Source: "..\bin\Release\net8.0-windows\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}";                        Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}";  Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}";                  Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{autostartup}\{#MyAppName}";                  Filename: "{app}\{#MyAppExeName}"; Tasks: startwithwindows

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Discovery (UDP 41173)"" dir=in action=allow protocol=UDP localport=41173 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Data (UDP 41174)"" dir=in action=allow protocol=UDP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host USB (TCP 41174)"" dir=in action=allow protocol=TCP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard mDNS (UDP 5353)"" dir=in action=allow protocol=UDP localport=5353"; Flags: runhidden
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Discovery (UDP 41173)"""; Flags: runhidden; RunOnceId: "FwDiscovery"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Data (UDP 41174)"""; Flags: runhidden; RunOnceId: "FwData"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host USB (TCP 41174)"""; Flags: runhidden; RunOnceId: "FwUsb"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard mDNS (UDP 5353)"""; Flags: runhidden; RunOnceId: "FwMdns"
