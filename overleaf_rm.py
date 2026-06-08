#!/usr/bin/env python3
"""Reemplazo de `pyoverleaf rm <Proyecto/ruta>` que lee la sesión de Overleaf
desde el navegador SALTEANDO Safari.

Mismo problema/solución que overleaf_write.py y overleaf_download.py: en macOS el
CLI de pyoverleaf llama a browser_cookie3.load(), que al tocar Safari lanza un
PermissionError (TCC) y aborta el login. Acá armamos el cookiejar solo con los
navegadores legibles.

Acepta varias rutas para borrarlas en una sola sesión:
    ./venv/bin/python3 overleaf_rm.py "Proyecto/a.jpeg" "Proyecto/b.jpeg" ...
"""
import http.cookiejar
import os
import sys

import browser_cookie3 as bc
from pyoverleaf import Api, ProjectIO

# Todos menos 'safari' (PermissionError en macOS) y 'lynx'/'w3m' (irrelevantes).
BROWSERS = ["chrome", "chromium", "brave", "edge", "vivaldi", "arc",
            "opera", "opera_gx", "firefox", "librewolf"]


def load_cookies(cookie_domain):
    jar = http.cookiejar.CookieJar()
    found = False
    for name in BROWSERS:
        fn = getattr(bc, name, None)
        if fn is None:
            continue
        try:
            for c in fn(domain_name=cookie_domain):
                jar.set_cookie(c)
                found = True
        except Exception:
            pass  # navegador no instalado / no legible: lo ignoramos
    if not found:
        sys.exit(f"ERROR: no se encontraron cookies de {cookie_domain} en "
                 f"ningún navegador legible {BROWSERS}. Logueate en Overleaf "
                 f"en Chrome/Firefox y reintentá.")
    return jar


def main():
    if len(sys.argv) < 2:
        sys.exit("Uso: overleaf_rm.py <Proyecto/ruta> [<Proyecto/ruta> ...]")
    host = os.environ.get("PYOVERLEAF_HOST", "www.overleaf.com")
    cookie_domain = host.removeprefix("www.")
    api = Api(host=host, timeout=120)
    api.login_from_cookies(load_cookies(cookie_domain))

    # Cacheamos un ProjectIO por proyecto para no re-resolver el id en cada borrado.
    ios = {}
    for path in sys.argv[1:]:
        if "/" not in path:
            print(f"  ! ruta inválida (falta <Proyecto>/): {path}", file=sys.stderr)
            continue
        if path.startswith("/"):
            path = path[1:]
        project, rel = path.split("/", 1)
        if project not in ios:
            pid = next((p.id for p in api.get_projects() if p.name == project), None)
            if pid is None:
                sys.exit(f"ERROR: no se encontró el proyecto '{project}' en Overleaf.")
            ios[project] = ProjectIO(api, pid)
        try:
            ios[project].remove(rel, missing_ok=True)
            print(f"  - borrado en Overleaf: {rel}")
        except Exception as e:
            print(f"  ! no se pudo borrar {rel}: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
