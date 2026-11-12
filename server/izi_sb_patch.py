#!/usr/bin/env python3
"""Добавляет в /root/signup_api.py третий формат подписки: sing-box (файл <token>.sb) для «Изи Защитника».

  python3 izi_sb_patch.py apply          — пропатчить signup_api.py (с резервной копией и проверкой)
  python3 izi_sb_patch.py sb-all --dry   — показать, для каких подписок будет создан .sb (ничего не пишет)
  python3 izi_sb_patch.py sb-all         — создать .sb рядом с каждой выданной подпиской

Существующие файлы подписок (Clash и .b64) команда sb-all не трогает.
"""
import os
import py_compile
import shutil
import subprocess
import sys
import time

TARGET = os.environ.get("SIGNUP_API", "/root/signup_api.py")
BACKUP_DIR = "/root/backups"
SERVICE = "kit-signup"

NEW_FUNCS = r'''
# ---- Формат sing-box для приложения «Изи Защитник» (файл <token>.sb рядом с подпиской) ----
SB_RULESETS = os.environ.get("SB_RULESETS", "")  # "local" = списки РФ вшиты в приложение (geoip-ru.srs, geosite-ru.srs)
SB_TEST_URL = os.environ.get("SB_TEST_URL", "https://www.gstatic.com/generate_204")


def singbox_outbound(e):
    """Одна ссылка (vless/trojan/hysteria2) -> outbound sing-box."""
    u = urllib.parse.urlparse(e["url"])
    q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
    cred = urllib.parse.unquote(u.username or "")
    alpn = [a for a in q.get("alpn", "").split(",") if a]
    tls = {"enabled": True, "server_name": q.get("sni") or u.hostname}
    if alpn:
        tls["alpn"] = alpn
    out = {"type": u.scheme, "tag": e["name"], "server": u.hostname, "server_port": u.port}
    if u.scheme == "hysteria2":
        out["password"] = cred
        out["tls"] = tls
        return out
    tls["utls"] = {"enabled": True, "fingerprint": q.get("fp", "chrome")}
    if u.scheme == "trojan":
        out["password"] = cred
        out["tls"] = tls
        if q.get("type") == "grpc":
            out["transport"] = {"type": "grpc", "service_name": q.get("serviceName", "")}
        return out
    if u.scheme != "vless":
        raise ValueError("неизвестный протокол: " + u.scheme)
    out["uuid"] = cred
    out["packet_encoding"] = "xudp"
    if q.get("type") == "ws":
        out["tls"] = tls
        out["transport"] = {"type": "ws", "path": q.get("path", "/")}
        if q.get("host"):
            out["transport"]["headers"] = {"Host": q["host"]}
        return out
    out["flow"] = q.get("flow", "xtls-rprx-vision")
    tls["reality"] = {"enabled": True, "public_key": q.get("pbk", ""), "short_id": q.get("sid", "")}
    out["tls"] = tls
    return out


def singbox_config(entries):
    """Полный конфиг sing-box 1.14: автовыбор живого сервера, российское напрямую."""
    servers = []
    for e in entries:
        try:
            servers.append(singbox_outbound(e))
        except Exception as ex:
            print("sing-box: пропущен сервер", e.get("name"), ex, flush=True)
    tags = [s["tag"] for s in servers]
    if not tags:
        raise RuntimeError("нет серверов для sing-box")
    suffixes = IPCHECK_DIRECT + ["ru", "xn--p1ai", "su"]
    rules = [
        {"action": "sniff"},
        {"protocol": "dns", "action": "hijack-dns"},
        {"ip_is_private": True, "outbound": "direct"},
        {"domain_suffix": suffixes, "outbound": "direct"},
        {"domain_keyword": ["gosuslugi"], "outbound": "direct"},
    ]
    dns_rules = [{"domain_suffix": suffixes, "server": "dns-local"}]
    route = {"rules": rules, "final": "proxy", "auto_detect_interface": True,
             "default_domain_resolver": "dns-local"}
    if SB_RULESETS == "local":
        route["rule_set"] = [
            {"type": "local", "tag": "geosite-ru", "format": "binary", "path": "geosite-ru.srs"},
            {"type": "local", "tag": "geoip-ru", "format": "binary", "path": "geoip-ru.srs"},
        ]
        rules.append({"rule_set": ["geosite-ru", "geoip-ru"], "outbound": "direct"})
        dns_rules.append({"rule_set": ["geosite-ru"], "server": "dns-local"})
    cfg = {
        "log": {"level": "warn"},
        "dns": {
            "servers": [
                {"type": "udp", "tag": "dns-local", "server": "77.88.8.8"},
                {"type": "https", "tag": "dns-remote", "server": "1.1.1.1", "detour": "proxy"},
            ],
            "rules": dns_rules,
            "final": "dns-remote",
            "strategy": "ipv4_only",
        },
        "inbounds": [{"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"],
                      "auto_route": True, "strict_route": True}],
        "outbounds": [
            {"type": "selector", "tag": "proxy", "outbounds": ["auto"] + tags, "default": "auto"},
            {"type": "urltest", "tag": "auto", "outbounds": tags, "url": SB_TEST_URL,
             "interval": "3m", "tolerance": 50},
        ] + servers + [{"type": "direct", "tag": "direct"}],
        "route": route,
    }
    return json.dumps(cfg, ensure_ascii=False, indent=1)


def sb_text(entries):
    """Конфиг sing-box или None. Ошибка здесь не должна ломать выдачу обычной подписки."""
    try:
        return singbox_config(entries)
    except Exception as e:
        print("sing-box config failed:", e, flush=True)
        return None


def _put_extra(put, name, text):
    if not text:
        return
    try:
        put(name + ".sb", text)
    except Exception as e:
        print("sing-box publish failed:", name, e, flush=True)


'''

# (старый текст, новый текст) — каждый старый фрагмент должен встретиться ровно один раз
EDITS = [
    ('def publish_sub(text, b64text=None):\n',
     NEW_FUNCS + 'def publish_sub(text, b64text=None, sbtext=None):\n'),
    ('            if b64text:\n                _put_moscow(token + ".b64", b64text)\n            return MOSCOW_SUB_BASE + token\n',
     '            if b64text:\n                _put_moscow(token + ".b64", b64text)\n            _put_extra(_put_moscow, token, sbtext)\n            return MOSCOW_SUB_BASE + token\n'),
    ('        if b64text:\n            _put_local(token + ".b64", b64text)\n        return LOCAL_SUB_BASE + token\n',
     '        if b64text:\n            _put_local(token + ".b64", b64text)\n        _put_extra(_put_local, token, sbtext)\n        return LOCAL_SUB_BASE + token\n'),
    ('    return publish_sub(clash_yaml(entries), b64), entries\n',
     '    return publish_sub(clash_yaml(entries), b64, sb_text(entries)), entries\n'),
    ('def refresh_all(dry=False):\n',
     'def refresh_all(dry=False, only_sb=False):\n'),
    ('            if n.endswith(".b64") or n.endswith(".html") or n.startswith("."):\n',
     '            if n.endswith(".b64") or n.endswith(".sb") or n.endswith(".html") or n.startswith("."):\n'),
    ('            names = [n for n in _ssh_moscow("ls -1 /var/www/sub").split() if not n.endswith(".b64")]\n',
     '            names = [n for n in _ssh_moscow("ls -1 /var/www/sub").split()\n'
     '                     if not n.endswith(".b64") and not n.endswith(".sb")]\n'),
    ('                built[slug] = (clash_yaml(ents), b64, len(ents))\n            yml, b64, cnt = built[slug]\n',
     '                built[slug] = (clash_yaml(ents), b64, len(ents), sb_text(ents))\n            yml, b64, cnt, sb = built[slug]\n'),
    ('            if where == "local":\n                _put_local(name, yml)\n                _put_local(name + ".b64", b64)\n'
     '            else:\n                _put_moscow(name, yml)\n                _put_moscow(name + ".b64", b64)\n',
     '            if where == "local":\n                if not only_sb:\n                    _put_local(name, yml)\n                    _put_local(name + ".b64", b64)\n'
     '                _put_extra(_put_local, name, sb)\n'
     '            else:\n                if not only_sb:\n                    _put_moscow(name, yml)\n                    _put_moscow(name + ".b64", b64)\n'
     '                _put_extra(_put_moscow, name, sb)\n'),
    ('    if sys.argv[1:2] == ["--refresh-all"]:\n',
     '    if sys.argv[1:2] == ["--sb-all"]:\n'
     '        # создать <token>.sb (формат sing-box) рядом с каждой выданной подпиской, остальные файлы не трогать\n'
     '        refresh_all(dry="--dry" in sys.argv, only_sb=True)\n'
     '        sys.exit(0)\n'
     '    if sys.argv[1:2] == ["--refresh-all"]:\n'),
]


def patched(src):
    for old, new in EDITS:
        n = src.count(old)
        if n != 1:
            raise SystemExit("СТОП: фрагмент найден %d раз(а), ожидался 1:\n%s" % (n, old[:120]))
        src = src.replace(old, new)
    return src


def apply():
    src = open(TARGET, encoding="utf-8").read()
    if "def singbox_config(" in src:
        print("OK: патч уже применён, ничего не меняю")
        return
    new = patched(src)
    tmp = TARGET + ".sbtmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(new)
    try:
        py_compile.compile(tmp, doraise=True)
    except Exception as e:
        os.remove(tmp)
        raise SystemExit("СТОП: после правки файл не компилируется, оригинал не тронут: %s" % e)
    os.makedirs(BACKUP_DIR, exist_ok=True)
    bak = os.path.join(BACKUP_DIR, "signup_api.py.%s" % time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(TARGET, bak)
    os.chmod(tmp, os.stat(TARGET).st_mode)
    os.replace(tmp, TARGET)
    print("OK: signup_api.py обновлён, резервная копия:", bak)
    print("    откат: cp %s %s && systemctl restart %s" % (bak, TARGET, SERVICE))


def service_env():
    """Переменные окружения работающей службы: без них signup_api.py не знает про Москву и Лондон."""
    pid = subprocess.run(["systemctl", "show", "-p", "MainPID", "--value", SERVICE],
                         capture_output=True, text=True).stdout.strip()
    if not pid or pid == "0":
        raise SystemExit("СТОП: служба %s не запущена, не могу взять её настройки" % SERVICE)
    env = dict(os.environ)
    with open("/proc/%s/environ" % pid, "rb") as f:
        for item in f.read().split(b"\0"):
            if b"=" in item:
                k, v = item.split(b"=", 1)
                env[k.decode()] = v.decode("utf-8", "replace")
    return env


def sb_all(dry):
    args = [sys.executable, TARGET, "--sb-all"] + (["--dry"] if dry else [])
    raise SystemExit(subprocess.run(args, env=service_env(), cwd=os.path.dirname(TARGET)).returncode)


if __name__ == "__main__":
    cmd = sys.argv[1:2]
    if cmd == ["apply"]:
        apply()
    elif cmd == ["sb-all"]:
        sb_all("--dry" in sys.argv)
    else:
        print(__doc__)
