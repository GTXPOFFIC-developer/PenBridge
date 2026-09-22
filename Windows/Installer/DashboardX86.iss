; ================================================================
; Dashboard Host — x86 (32-bit) Installer (DashboardX86.exe)
; Works on both 32-bit and 64-bit Windows
; ================================================================

#define MyAppName     "Dashboard Host"
#define MyAppVersion  "1.0.0"
#define MyAppPublisher "Dashboard"
#define MyAppExeName  "DashboardHost.exe"
#define MyArch        "x86"

[Setup]
; Separate AppId so x86 and x64 can coexist side-by-side if needed
AppId={{5E97D4B6-7C12-4EA4-A651-38A201A94003}
AppName={#MyAppName} (32-bit)
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion} ({#MyArch})
DefaultDirName={autopf32}\Dashboard Host
DefaultGroupName={#MyAppName} (32-bit)
AllowNoIcons=yes
OutputDir=..\bin\Installer
OutputBaseFilename=DashboardX86
SetupIconFile=..\app.ico
UninstallDisplayIcon={app}\DashboardHost.exe
Compression=lzma2/max
SolidCompression=yes
; Allow both 32-bit and 64-bit Windows
ArchitecturesAllowed=x86 x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
WizardStyle=modern
WizardImageStretch=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon";      Description: "{cm:CreateDesktopIcon}";      GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startwithwindows"; Description: "Start Dashboard Host automatically when Windows starts"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
; 32-bit publish output
Source: "..\bin\Release\net8.0-windows\win-x86\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName} (32-bit)";               Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}";  Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName} (32-bit)";         Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{autostartup}\{#MyAppName} (32-bit)";         Filename: "{app}\{#MyAppExeName}"; Tasks: startwithwindows

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Discovery x86 (UDP 41173)"" dir=in action=allow protocol=UDP localport=41173 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host Data x86 (UDP 41174)"" dir=in action=allow protocol=UDP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard Host USB x86 (TCP 41174)"" dir=in action=allow protocol=TCP localport=41174 program=""{app}\{#MyAppExeName}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Dashboard mDNS x86 (UDP 5353)"" dir=in action=allow protocol=UDP localport=5353"; Flags: runhidden
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Discovery x86 (UDP 41173)"""; Flags: runhidden; RunOnceId: "FwDiscovery86"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host Data x86 (UDP 41174)"""; Flags: runhidden; RunOnceId: "FwData86"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard Host USB x86 (TCP 41174)"""; Flags: runhidden; RunOnceId: "FwUsb86"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Dashboard mDNS x86 (UDP 5353)"""; Flags: runhidden; RunOnceId: "FwMdns86"
