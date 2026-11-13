Unicode true

####
## Установщик «Изи Защитника» для Windows. Собирается командой wails build -nsis
## (файл wails_tools.nsh Wails создаёт сам при каждой сборке).
####
## Файл сохранён в UTF-8 с BOM, чтобы русские надписи читались правильно.
## Названия заданы здесь: тогда значения из wails_tools.nsh (файл без BOM) не используются.
!define INFO_PROJECTNAME    "IziZashchitnik"
!define INFO_COMPANYNAME    "IZI"
!define INFO_PRODUCTNAME    "Изи Защитник"
!define INFO_COPYRIGHT      "IZI"
!define PRODUCT_EXECUTABLE  "IziZashchitnik.exe"
!define UNINST_KEY_NAME     "IziZashchitnik"

!include "wails_tools.nsh"

# Номер версии для Windows должен состоять из 4 частей
VIProductVersion "${INFO_PRODUCTVERSION}.0"
VIFileVersion    "${INFO_PRODUCTVERSION}.0"

VIAddVersionKey "CompanyName"     "${INFO_COMPANYNAME}"
VIAddVersionKey "FileDescription" "${INFO_PRODUCTNAME} — установка"
VIAddVersionKey "ProductVersion"  "${INFO_PRODUCTVERSION}"
VIAddVersionKey "FileVersion"     "${INFO_PRODUCTVERSION}"
VIAddVersionKey "LegalCopyright"  "${INFO_COPYRIGHT}"
VIAddVersionKey "ProductName"     "${INFO_PRODUCTNAME}"

ManifestDPIAware true

!include "MUI.nsh"

!define MUI_ICON "..\icon.ico"
!define MUI_UNICON "..\icon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${PRODUCT_EXECUTABLE}"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить «Изи Защитник»"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"

Name "${INFO_PRODUCTNAME}"
OutFile "..\..\bin\${INFO_PROJECTNAME}-${ARCH}-installer.exe"
InstallDir "$PROGRAMFILES64\IziZashchitnik"
ShowInstDetails nevershow
ShowUninstDetails nevershow

Function .onInit
   !insertmacro wails.checkArchitecture
FunctionEnd

Section
    !insertmacro wails.setShellContext

    # Если программа запущена (обновление поверх), закрываем её, иначе файл не заменить.
    nsExec::Exec 'taskkill /F /IM "${PRODUCT_EXECUTABLE}"'
    Sleep 800

    !insertmacro wails.webview2runtime

    SetOutPath $INSTDIR

    !insertmacro wails.files

    CreateShortcut "$SMPROGRAMS\${INFO_PRODUCTNAME}.lnk" "$INSTDIR\${PRODUCT_EXECUTABLE}"
    CreateShortCut "$DESKTOP\${INFO_PRODUCTNAME}.lnk" "$INSTDIR\${PRODUCT_EXECUTABLE}"

    # Ссылка izi-zashchitnik:// — кнопка «Добавить» на сайте открывает программу.
    !insertmacro wails.associateCustomProtocols

    !insertmacro wails.writeUninstaller
SectionEnd

Section "uninstall"
    !insertmacro wails.setShellContext

    nsExec::Exec 'taskkill /F /IM "${PRODUCT_EXECUTABLE}"'
    Sleep 800

    RMDir /r $INSTDIR

    Delete "$SMPROGRAMS\${INFO_PRODUCTNAME}.lnk"
    Delete "$DESKTOP\${INFO_PRODUCTNAME}.lnk"

    !insertmacro wails.unassociateCustomProtocols

    !insertmacro wails.deleteUninstaller
SectionEnd
