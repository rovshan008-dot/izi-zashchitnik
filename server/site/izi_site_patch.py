#!/usr/bin/env python3
"""Добавляет на лендинг «Цифровая безопасность» кнопки «Изи Защитник» (Android и Windows)
и кладёт рядом страницы установки и сами программы.

Запускать на сервере, где лежит лендинг (порт 8088):

  python3 izi_site_patch.py check    — только показать, что будет сделано (ничего не меняет)
  python3 izi_site_patch.py apply    — скачать страницы и программы, добавить недостающие кнопки
  python3 izi_site_patch.py apk      — только обновить файлы программ (после новой сборки)

Что делает apply:
  1) кладёт рядом с лендингом страницы установки (ustanovka.html — Android, ustanovka-windows.html — Windows)
     и файлы программ (izi-zashchitnik.apk, izi-zashchitnik-windows.exe);
  2) добавляет в блок «Скачайте приложение» плитки «Изи Защитник» для Android и для Windows;
  3) добавляет в карточку с выданной ссылкой кнопку «Изи Защитник» для Android.
Существующие кнопки и тексты не меняются; уже добавленное повторно не добавляется.
Перед правкой делается резервная копия лендинга.
"""
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

REPO_RAW = "https://raw.githubusercontent.com/rovshan008-dot/izi-zashchitnik/"
BRANCH = os.environ.get("IZI_BRANCH", "main")
PAGES = ["ustanovka.html", "ustanovka-windows.html"]
# Файлы программ. Менять при новой сборке.
APK_URL = os.environ.get(
    "IZI_APK_URL",
    "https://github.com/rovshan008-dot/izi-zashchitnik/releases/download/build-6/IziZashchitnik-0.5.0-universal.apk",
)
WIN_URL = os.environ.get(
    "IZI_WIN_URL",
    "https://github.com/rovshan008-dot/izi-zashchitnik/releases/download/windows-v0.1.1/IziZashchitnik-Windows-0.1.1-setup.exe",
)
APK_NAME = "izi-zashchitnik.apk"
WIN_NAME = "izi-zashchitnik-windows.exe"
PORT = os.environ.get("IZI_SITE_PORT", "8088")
BACKUP_DIR = "/root/backups"

SHIELD = '<svg viewBox="0 0 24 24"><path d="M12 2l8 3v6c0 5-3.400 9.400-8 11-4.600-1.600-8-6-8-11V5z"/></svg>'

TILE = (
    '            <a id="iziTile" href="ustanovka.html" target="_blank" rel="noopener">' + SHIELD +
    '<span><small>Android · наше приложение</small><b>Изи Защитник</b></span></a>\n'
)
WIN_TILE = (
    '            <a id="iziWinTile" href="ustanovka-windows.html" target="_blank" rel="noopener">' + SHIELD +
    '<span><small>Windows · наше приложение</small><b>Изи Защитник</b></span></a>\n'
)
TILE_UPDATE = (
    "    if (iziMain && iziTile) iziTile.href = 'ustanovka.html?url=' + encodeURIComponent(iziMain.url)"
    " + '&name=' + encodeURIComponent(iziMain.name);"
)
WIN_TILE_UPDATE = (
    "\n    const iziWinTile = $('#iziWinTile');\n"
    "    if (iziMain && iziWinTile) iziWinTile.href = 'ustanovka-windows.html?url=' + encodeURIComponent(iziMain.url)"
    " + '&name=' + encodeURIComponent(iziMain.name);"
)

# (признак, что правка уже сделана, старый текст, новый текст). Старый текст должен встретиться ровно один раз.
EDITS = [
    # плитка Android, после остальных плиток для Android
    ('id="iziTile"',
     '<small>Android · GitHub</small><b>v2rayNG</b></span></a>\n',
     '<small>Android · GitHub</small><b>v2rayNG</b></span></a>\n' + TILE),
    # кнопка в карточке с выданной ссылкой
    ('Изи Защитник · наше приложение для Android',
     '">Открыть в Hiddify</a></div></div>`;',
     '">Открыть в Hiddify</a>` +\n'
     '          `<a class="btn ghost" href="ustanovka.html?url=${enc}&name=${nm}" target="_blank" rel="noopener">'
     'Изи Защитник · наше приложение для Android</a></div></div>`;'),
    # когда ссылка выдана, плитка ведёт на установку уже с этой ссылкой
    ("iziMain",
     "const list = $('#srvList'); list.innerHTML = '';",
     "const list = $('#srvList'); list.innerHTML = '';\n"
     "    const iziMain = servers.find(s => s.kind !== 'vless'), iziTile = $('#iziTile');\n" + TILE_UPDATE),
    # плитка Windows, после плитки Karing для Windows
    ('id="iziWinTile"',
     '<small>Windows · с сайта</small><b>Karing</b></span></a>\n',
     '<small>Windows · с сайта</small><b>Karing</b></span></a>\n' + WIN_TILE),
    # то же для плитки Windows
    ("const iziWinTile",
     TILE_UPDATE,
     TILE_UPDATE + WIN_TILE_UPDATE),
]


def find_site_root():
    """Папка лендинга: берём root из блока nginx, который слушает нужный порт."""
    given = os.environ.get("IZI_SITE_ROOT")
    if given:
        return given
    conf = subprocess.run(["nginx", "-T"], capture_output=True, text=True).stdout
    for block in re.split(r"\n\s*server\s*\{", conf)[1:]:
        if re.search(r"listen\s+[^;]*\b%s\b" % re.escape(PORT), block):
            m = re.search(r"\n\s*root\s+([^;]+);", block)
            if m:
                return m.group(1).strip().strip('"')
    raise SystemExit(
        "СТОП: не нашёл в настройках nginx папку сайта на порту %s.\n"
        "Укажите её вручную: IZI_SITE_ROOT=/путь/к/папке python3 izi_site_patch.py apply" % PORT
    )


def find_index(root):
    for name in ("index.html", "index.htm"):
        path = os.path.join(root, name)
        if os.path.isfile(path):
            return path
    raise SystemExit("СТОП: в папке %s нет index.html" % root)


def download(url, dest):
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "izi-site-patch"})
    with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    return tmp


def fetch_pages(root):
    result = []
    for name in PAGES:
        tmp = download(REPO_RAW + BRANCH + "/server/site/" + name, os.path.join(root, name))
        text = open(tmp, encoding="utf-8").read()
        if "izi-zashchitnik://add" not in text:
            os.remove(tmp)
            raise SystemExit("СТОП: скачалось что-то не то вместо страницы " + name)
        result.append((tmp, name))
    return result


def fetch_binary(root, url, name, magic, min_size):
    tmp = download(url, os.path.join(root, name))
    size = os.path.getsize(tmp)
    with open(tmp, "rb") as f:
        head = f.read(2)
    if head != magic or size < min_size:
        os.remove(tmp)
        raise SystemExit("СТОП: скачанный файл не похож на программу (%d байт): %s" % (size, url))
    return tmp, size


def fetch_programs(root):
    apk = fetch_binary(root, APK_URL, APK_NAME, b"PK", 10 * 1024 * 1024)
    win = fetch_binary(root, WIN_URL, WIN_NAME, b"MZ", 5 * 1024 * 1024)
    return [(apk[0], APK_NAME, apk[1]), (win[0], WIN_NAME, win[1])]


def pending_edits(src):
    return [edit for edit in EDITS if edit[0] not in src]


def patched(src):
    for _, old, new in pending_edits(src):
        n = src.count(old)
        if n != 1:
            raise SystemExit("СТОП: фрагмент найден %d раз(а), ожидался 1:\n%s" % (n, old[:120]))
        src = src.replace(old, new)
    return src


def publish(tmp, dest, like):
    """Ставит файл на место с теми же владельцем и правами, что у лендинга."""
    st = os.stat(like)
    os.chmod(tmp, st.st_mode)
    try:
        os.chown(tmp, st.st_uid, st.st_gid)
    except PermissionError:
        pass
    os.replace(tmp, dest)


def main():
    cmd = sys.argv[1:2]
    if cmd not in (["check"], ["apply"], ["apk"]):
        print(__doc__)
        return
    root = find_site_root()
    index = find_index(root)
    print("Папка сайта:", root)

    if cmd == ["apk"]:
        for tmp, name, size in fetch_programs(root):
            publish(tmp, os.path.join(root, name), index)
            print("OK: %s обновлён, %.0f МБ" % (name, size / 1048576))
        return

    src = open(index, encoding="utf-8").read()
    todo = pending_edits(src)
    new = patched(src) if todo else src

    if cmd == ["check"]:
        print("Лендинг:", index)
        print("Кнопки:", "будут добавлены (%d правки)" % len(todo) if todo else "все уже на месте")
        print("Будут положены рядом:", ", ".join(PAGES + [APK_NAME, WIN_NAME]))
        print("Ничего не изменено.")
        return

    # Сначала всё скачиваем: если что-то не скачалось, лендинг остаётся нетронутым.
    pages = fetch_pages(root)
    programs = fetch_programs(root)
    for tmp, name in pages:
        publish(tmp, os.path.join(root, name), index)
    for tmp, name, size in programs:
        publish(tmp, os.path.join(root, name), index)
        print("OK: %s на месте, %.0f МБ" % (name, size / 1048576))
    print("OK: страницы установки на месте:", ", ".join(PAGES))

    if not todo:
        print("OK: все кнопки на лендинге уже есть, лендинг не трогаю")
        return
    os.makedirs(BACKUP_DIR, exist_ok=True)
    bak = os.path.join(BACKUP_DIR, "landing-index.html.%s" % time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(index, bak)
    tmp = index + ".izitmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(new)
    publish(tmp, index, bak)
    print("OK: добавлено правок: %d, резервная копия лендинга: %s" % (len(todo), bak))
    print("    откат: cp %s %s" % (bak, index))


if __name__ == "__main__":
    main()
