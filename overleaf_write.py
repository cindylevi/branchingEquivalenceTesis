#!/usr/bin/env python3
"""Reemplazo de `pyoverleaf write <Proyecto/ruta>` que lee la sesión de
Overleaf desde el navegador SALTEANDO Safari.

En macOS, `pyoverleaf write` llama a browser_cookie3.load(), que recorre todos
los navegadores; al llegar a Safari lanza un PermissionError (las cookies de
Safari están protegidas por TCC) que aborta todo el login, incluso si estás
logueada en Chrome/Firefox. Este script arma el cookiejar solo con los
navegadores legibles y luego usa la API de pyoverleaf igual que el CLI.

Uso (idéntico a `pyoverleaf write`):
    cat archivo.tex | ./venv/bin/python3 overleaf_write.py "Proyecto/ruta/archivo.tex"
"""
import http.cookiejar
import os
import shutil
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


def get_io_and_path(api, path):
    if "/" not in path:
        sys.exit("ERROR: la ruta debe tener formato <Proyecto>/<ruta local>.")
    if path.startswith("/"):
        path = path[1:]
    project, rel = path.split("/", 1)
    project_id = next((p.id for p in api.get_projects() if p.name == project), None)
    if project_id is None:
        sys.exit(f"ERROR: no se encontró el proyecto '{project}' en Overleaf.")
    return ProjectIO(api, project_id), rel


def main():
    if len(sys.argv) != 2:
        sys.exit("Uso: overleaf_write.py <Proyecto/ruta/archivo>")
    path = sys.argv[1]
    # Usamos www.overleaf.com como host: overleaf.com redirige a www y el
    # cliente de websocket no sabe seguir el redirect (rompe con "scheme
    # https is invalid"). Las cookies igual son del dominio base .overleaf.com.
    host = os.environ.get("PYOVERLEAF_HOST", "www.overleaf.com")
    cookie_domain = host.removeprefix("www.")
    api = Api(host=host)
    api.login_from_cookies(load_cookies(cookie_domain))
    io, rel = get_io_and_path(api, path)

    # Overleaf, al recibir el upload, interpreta los bytes como Latin-1 y los
    # re-guarda como UTF-8 (doble codificación: 'Í' -> 'Ã\x8d'). Para
    # compensar, mandamos el texto codificado en Latin-1: Overleaf lo
    # "des-hace" y queda UTF-8 correcto. Esto solo funciona con caracteres
    # <= U+00FF (todo el español entra); si hay alguno fuera de rango
    # (em-dash, comillas tipográficas, etc.) fallamos en vez de corromper.
    data = sys.stdin.buffer.read()
    try:
        compensated = data.decode("utf-8").encode("latin-1")
    except UnicodeDecodeError as e:
        sys.exit(f"ERROR: '{rel}' no es UTF-8 válido: {e}")
    except UnicodeEncodeError as e:
        bad = data.decode("utf-8")[e.start:e.end]
        sys.exit(f"ERROR: '{rel}' tiene caracteres fuera de Latin-1 que "
                 f"Overleaf corrompería: {bad!r} (U+{ord(bad[0]):04X}). "
                 f"Reemplazalos por equivalentes ASCII/LaTeX (ej. '--' por "
                 f"en-dash, \"'\" por comilla tipográfica) y reintentá.")
    with io.open(rel, "wb+") as f:
        f.write(compensated)


if __name__ == "__main__":
    main()
