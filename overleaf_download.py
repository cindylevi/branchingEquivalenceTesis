#!/usr/bin/env python3
"""Reemplazo de `pyoverleaf download-project <Proyecto> <salida.zip>` que lee la
sesión de Overleaf desde el navegador SALTEANDO Safari.

En macOS, `pyoverleaf download-project` llama a browser_cookie3.load(), que
recorre todos los navegadores; al llegar a Safari lanza un PermissionError (las
cookies de Safari están protegidas por TCC) que aborta todo el login, incluso si
estás logueada en Chrome/Firefox. Este script arma el cookiejar solo con los
navegadores legibles y luego usa la API de pyoverleaf igual que el CLI.

Es el gemelo de overleaf_write.py (que resuelve el mismo problema en la subida).

Uso (idéntico a `pyoverleaf download-project`):
    ./venv/bin/python3 overleaf_download.py "Proyecto" salida.zip
"""
import http.cookiejar
import os
import sys

import browser_cookie3 as bc
from pyoverleaf import Api

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
    if len(sys.argv) != 3:
        sys.exit("Uso: overleaf_download.py <Proyecto> <salida.zip>")
    project, output_path = sys.argv[1], sys.argv[2]
    # Usamos www.overleaf.com como host: overleaf.com redirige a www. Las
    # cookies igual son del dominio base .overleaf.com.
    host = os.environ.get("PYOVERLEAF_HOST", "www.overleaf.com")
    cookie_domain = host.removeprefix("www.")
    # timeout amplio: generar y bajar el zip del proyecto tarda más que los 16s
    # por defecto del cliente.
    api = Api(host=host, timeout=120)
    api.login_from_cookies(load_cookies(cookie_domain))

    project_id = next((p.id for p in api.get_projects() if p.name == project), None)
    if project_id is None:
        sys.exit(f"ERROR: no se encontró el proyecto '{project}' en Overleaf.")
    api.download_project(project_id, output_path)


if __name__ == "__main__":
    main()
