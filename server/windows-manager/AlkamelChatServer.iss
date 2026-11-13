#define AppName "Alkamel Chat Server"
#define AppVersion "0.1.0"
[Setup]
AppId={{8E95DA67-5E44-4F89-A9C8-2F8C8B1A9001}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={autopf}\Alkamel Chat Server
DefaultGroupName={#AppName}
OutputDir=..\..\output
OutputBaseFilename=Alkamel-Chat-Server-Setup
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=admin
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\AlkamelChatServer.exe

[Files]
Source: "..\..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\output\alkamel-chat-turn.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\خادم شبكة الكامل - الدردشة"; Filename: "{app}\AlkamelChatServer.exe"
Name: "{autodesktop}\خادم شبكة الكامل - الدردشة"; Filename: "{app}\AlkamelChatServer.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "إنشاء اختصار على سطح المكتب"; GroupDescription: "اختصارات:"

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Alkamel Chat TURN"" dir=in action=allow protocol=UDP localport=3478,49160-49200"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Alkamel Chat TURN Credentials"" dir=in action=allow protocol=TCP localport=3479"; Flags: runhidden
Filename: "{app}\AlkamelChatServer.exe"; Description: "تشغيل خادم الدردشة"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Alkamel Chat TURN"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Alkamel Chat TURN Credentials"""; Flags: runhidden
