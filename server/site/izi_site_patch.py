#!/usr/bin/env python3
"""Добавляет на лендинг «Цифровая безопасность» отдельную кнопку «Изи Защитник» и страницу установки.

Запускать на сервере, где лежит лендинг (порт 8088):

  python3 izi_site_patch.py check    — только показать, что будет сделано (ничего не меняет)
  python3 izi_site_patch.py apply    — скачать страницу установки и приложение, добавить кнопки
  python3 izi_site_patch.py apk      — только обновить файл приложения (после новой сборки)

Что делает apply:
  1) кладёт рядом с лендингом ustanovka.html (страница установки) и izi-zashchitnik.apk (само приложение);
  2) добавляет в блок «Скачайте приложение» плитку «Android · наше приложение — Изи Защитник»;
  3) добавляет в карточку с выданной ссылкой третью кнопку «Изи Защитник», рядом с Karing и Hiddify.
Существующие кнопки и тексты не меняются. Перед правкой делается резервная копия лендинга.
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
PAGE_URL = REPO_RAW + BRANCH + "/server/site/ustanovka.html"
# Файл приложения: универсальная сборка, подходит для любых телефонов. Менять при новой сборке.
APK_URL = os.environ.get(
    "IZI_APK_URL",
    "https://github.com/rovshan008-dot/izi-zashchitnik/releases/download/build-6/IziZashchitnik-0.5.0-universal.apk",
)
APK_NAME = "izi-zashchitnik.apk"
PAGE_NAME = "ustanovka.html"
PORT = os.environ.get("IZI_SITE_PORT", "8088")
BACKUP_DIR = "/root/backups"

TILE = (
    '            <a id="iziTile" href="ustanovka.html" target="_blank" rel="noopener">'
    '<svg viewBox="0 0 24 24"><path d="M12 2l8 3v6c0 5-3.400 9.400-8 11-4.600-1.600-8-6-8-11V5z"/></svg>'
    '<span><small>Android · наше приложение</small><b>Изи Защитник</b></span></a>\n'
)

# (старый текст, новый текст) — каждый старый фрагмент должен встретиться ровно один раз
EDITS = [
    # плитка в блоке «Скачайте приложение», после остальных плиток для Android
    ('<small>Android · GitHub</small><b>v2rayNG</b></span></a>\n',
     '<small>Android · GitHub</small><b>v2rayNG</b></span></a>\n' + TILE),
    # третья кнопка в карточке с выданной ссылкой
    ('">Открыть в Hiddify</a></div></div>`;',
     '">Открыть в Hiddify</a>` +\n'
     '          `<a class="btn ghost" href="ustanovka.html?url=${enc}&name=${nm}" target="_blank" rel="noopener">'
     'Изи Защитник · наше приложение для Android</a></div></div>`;'),
    # когда ссылка выдана, плитка тоже ведёт на установку уже с этой ссылкой
    ("const list = $('#srvList'); list.innerHTML = '';",
     "const list = $('#srvList'); list.innerHTML = '';\n"
     "    const iziMain = servers.find(s => s.kind !== 'vless'), iziTile = $('#iziTile');\n"
     "    if (iziMain && iziTile) iziTile.href = 'ustanovka.html?url=' + encodeURIComponent(iziMain.url)"
     " + '&name=' + encodeURIComponent(iziMain.name);"),
]


def find_site_root():
    """Папка лендинга: берём root из блока nginx, который слушает нужный порт."""
    given = os.environ.get("IZI_SITE_ROOT")
    if given:
        return given
    conf = subprocess.run(["nginx", "-T"], capture_output=True, text=True).stdout
    # грубо режем на блоки server { ... } и ищем тот, где есть listen <порт>
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
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    return tmp


def fetch_page(root):
    tmp = download(PAGE_URL, os.path.join(root, PAGE_NAME))
    text = open(tmp, encoding="utf-8").read()
    if "izi-zashchitnik://add" not in text or "APK_URL" not in text:
        os.remove(tmp)
        raise SystemExit("СТОП: скачалось что-то не то вместо страницы установки: " + PAGE_URL)
    return tmp


def fetch_apk(root):
    tmp = download(APK_URL, os.path.join(root, APK_NAME))
    size = os.path.getsize(tmp)
    with open(tmp, "rb") as f:
        magic = f.read(2)
    if magic != b"PK" or size < 10 * 1024 * 1024:
        os.remove(tmp)
        raise SystemExit("СТОП: скачанный файл не похож на приложение (%d байт): %s" % (size, APK_URL))
    return tmp, size


def patched(src):
    for old, new in EDITS:
        n = src.count(old)
        if n != 1:
            raise SystemExit("СТОП: фрагмент найден %d раз(а), ожидался 1:\n%s" % (n, old[:120]))
        src = src.replace(old, new)
    return src


def publish(tmp, dest, like):
    """Ставит скачанный файл на место с теми же владельцем и правами, что у лендинга."""
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
        tmp, size = fetch_apk(root)
        publish(tmp, os.path.join(root, APK_NAME), index)
        print("OK: приложение обновлено, %.0f МБ: %s" % (size / 1048576, os.path.join(root, APK_NAME)))
        return

    src = open(index, encoding="utf-8").read()
    already = 'id="iziTile"' in src
    new = src if already else patched(src)

    if cmd == ["check"]:
        print("Лендинг:", index)
        print("Кнопки:", "уже добавлены" if already else "будут добавлены (%d правки)" % len(EDITS))
        print("Будут положены рядом:", PAGE_NAME, "и", APK_NAME)
        print("Ничего не изменено.")
        return

    # Сначала всё скачиваем: если что-то не скачалось, лендинг остаётся нетронутым.
    page_tmp = fetch_page(root)
    apk_tmp, size = fetch_apk(root)
    publish(page_tmp, os.path.join(root, PAGE_NAME), index)
    publish(apk_tmp, os.path.join(root, APK_NAME), index)
    print("OK: страница установки и приложение (%.0f МБ) на месте" % (size / 1048576))

    if already:
        print("OK: кнопки на лендинге уже есть, лендинг не трогаю")
        return
    os.makedirs(BACKUP_DIR, exist_ok=True)
    bak = os.path.join(BACKUP_DIR, "landing-index.html.%s" % time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(index, bak)
    tmp = index + ".izitmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(new)
    publish(tmp, index, bak)
    print("OK: кнопки добавлены, резервная копия лендинга:", bak)
    print("    откат: cp %s %s" % (bak, index))


if __name__ == "__main__":
    main()
