#!/usr/bin/env python3
"""
Clasifica instancias de minimizacion (configs_*.json) por familia del benchmark
usando el vocabulario de sus etiquetas.

Uso:
  python3 classify_families.py discover  <dir>              # descubre clusters de vocabulario
  python3 classify_families.py classify <dir> [out.csv]     # asigna familia con FAMILY_KEYWORDS

El campo 'instance' del CSV coincide con el nombre base del .lts (== modelName del
CSV de resultados sin la extension), para poder joinear ambos por esa columna.

Solo requiere Python 3 (libreria estandar, sin dependencias).
"""
import json, glob, os, re, sys, csv
from collections import Counter, defaultdict

NUM_RE = re.compile(r'^\d+$')

def normalize_label(l):
    """Etiqueta cruda -> conjunto de tokens de vocabulario (sin indices ni prefijos)."""
    l = l.strip()
    l = re.sub(r'^r_\d+', '', l)      # prefijo de renaming r_<n>
    l = re.sub(r'^c_', '', l)         # prefijo de controlable c_
    l = re.sub(r'^tau[._]?', '', l)   # marca tau
    toks = set()
    for p in l.split('.'):
        p = re.sub(r'^c_', '', p.lower())
        if p and not NUM_RE.match(p):
            toks.add(p)
    return toks

def instance_tokens(path):
    d = json.load(open(path))
    labels = set()
    for k in ('localAlphabet', 'translatedControllableLabels',
              'localUncontrollableAndFormulaLabels', 'relevantLabelsFromFormula'):
        v = d.get(k)
        if isinstance(v, list):
            labels.update(v)
    toks = set()
    for l in labels:
        toks |= normalize_label(l)
    return toks

def iter_configs(root):
    yield from glob.glob(os.path.join(root, '**', 'configs_*.json'), recursive=True)

def basename_key(path):
    return os.path.basename(path).replace('configs_', '').replace('.json', '')

def parse_nk(base):
    m = re.search(r'states_(\d+)_iter_(\d+)', base)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)

# --- diccionario editable: token distintivo -> familia (completar tras 'discover') ---
FAMILY_KEYWORDS = {
    'phil': 'DP', 'fork': 'DP', 'arise': 'DP', 'sitdown': 'DP',
    'cat': 'CM', 'mouse': 'CM',
    'airplane': 'AT', 'height': 'AT', 'ramp': 'AT',
    'buffer': 'TL', 'machine': 'TL', 'oven': 'TL', 'getintray': 'TL', 'putouttray': 'TL',
    'assign': 'BW', 'bid': 'BW', 'approve': 'BW', 'refuse': 'BW',
    'gripper': 'GR', 'ball': 'GR',
    'obstacle': 'MO',
    'robot': 'RS', 'drone': 'RS', 'uav': 'RS', 'search': 'RS',
}

def cmd_discover(root):
    insts = {p: instance_tokens(p) for p in iter_configs(root)}
    N = len(insts)
    if N == 0:
        print("no se encontraron configs_*.json en", root); return
    df = Counter()
    for t in insts.values():
        df.update(t)
    generic = {tok for tok, c in df.items() if c > 0.5 * N}  # tokens ubicuos = genericos

    parent = {}
    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    def union(a, b):
        parent[find(a)] = find(b)

    for p, t in insts.items():
        parent.setdefault(p, p)
        for tok in t - generic:
            union(p, ('tok', tok))

    comps = defaultdict(list)
    for p in insts:
        comps[find(p)].append(p)
    clusters = [[m for m in ms if m in insts] for ms in comps.values()]
    clusters = [c for c in clusters if c]

    print(f"{N} instancias | {len(df)} tokens | {len(generic)} genericos descartados: {sorted(generic)}\n")
    print(f"{len(clusters)} clusters de vocabulario:\n")
    for files in sorted(clusters, key=len, reverse=True):
        tokc = Counter()
        for f in files:
            tokc.update(insts[f] - generic)
        top = ', '.join(f"{t}({c})" for t, c in tokc.most_common(10))
        print(f"  [{len(files):5d}]  {top}")
        print(f"           ej: {basename_key(files[0])}")

def cmd_classify(root, out='families.csv'):
    rows, unknown, fam_count = [], [], Counter()
    for p in iter_configs(root):
        t = instance_tokens(p)
        hits = {FAMILY_KEYWORDS[tok] for tok in t if tok in FAMILY_KEYWORDS}
        base = basename_key(p); n, it = parse_nk(base)
        if len(hits) == 1:
            fam = next(iter(hits))
        elif not hits:
            fam = 'UNKNOWN'; unknown.append(base)
        else:
            fam = 'AMBIGUOUS:' + '|'.join(sorted(hits)); unknown.append(base)
        fam_count[fam] += 1
        rows.append((base, fam, n, it))
    with open(out, 'w', newline='') as f:
        w = csv.writer(f); w.writerow(['instance', 'family', 'states', 'iter']); w.writerows(rows)
    print(f"escrito {out} con {len(rows)} filas\n")
    for fam, c in fam_count.most_common():
        print(f"  {fam:22s} {c}")
    if unknown:
        print(f"\n{len(unknown)} sin clasificar (revisar keywords), ej: {unknown[:8]}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    cmd, root = sys.argv[1], sys.argv[2]
    if cmd == 'discover':
        cmd_discover(root)
    elif cmd == 'classify':
        cmd_classify(root, sys.argv[3] if len(sys.argv) > 3 else 'families.csv')
    else:
        print(__doc__)
