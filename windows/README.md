# Изи Защитник для Windows

Программа на Go: внутри ядро sing-box (та же версия, что в приложении для Android), окно — Wails (WebView2),
экран — `frontend/dist` (обычные HTML, CSS и JS, без сборки).

- `core.go` — подключения, загрузка подписки (сначала ссылка + `.sb`), включение и выключение ядра, выбор сервера.
- `app.go` — методы, которые вызывает экран; ссылка `izi-zashchitnik://add?url=...` с сайта.
- `main.go` — окно программы.
- `build/windows/wails.exe.manifest` — программа просит права администратора: без них не создать сетевой адаптер.
- `build/windows/installer/project.nsi` — установщик на русском (ярлыки, удаление, ссылка `izi-zashchitnik://`).

Данные: `%APPDATA%\IziZashchitnik` (подключения, журнал ядра `core.log`).

Сборка — GitHub Actions, `.github/workflows/windows.yml`. Версия — `info.productVersion` в `wails.json`;
на одну версию выкладывается одна сборка (`windows-v<версия>`).
