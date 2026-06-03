# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A Licenciatura thesis (in **Spanish**), not a software project. The deliverable is `tesis.pdf`, built from LaTeX sources. The thesis is *about* a Java implementation of Groote–Jansen–Keiren–Wijs (2019) branching bisimilarity that replaces WSOE inside MTSA's compositional controller-synthesis flow. The implementation itself lives elsewhere (in MTSA, `mtstools/DCS/Compositional/BranchingEquivalence.java`); this repo only carries three frozen snapshots of it under `versiones/` as study material for the Implementation chapter.

- **Title**: *Escalando la síntesis composicional de controladores mediante una nueva técnica de minimización para branching bisimilarity*
- **Director**: Sebastian Uchitel — **Codirector**: Hernán Gabriel Gagliardi
- Written in Spanish — keep new prose, comments, figure captions, and labels in Spanish to match.

## Build

Main entry point: `tesis.tex` (uses the custom `tesis.cls` class file in the repo root — do not assume a TeX-Live default class).

```sh
pdflatex tesis.tex
bibtex tesis            # if bibliography references changed
pdflatex tesis.tex
pdflatex tesis.tex      # twice for refs/TOC
```

Or use `latexmk -pdf tesis.tex` if available. `tesis.cls` already enables `twoside` book layout via the doc options on line 1 of `tesis.tex`; the comment there documents the alternative (single-sided) form.

## Editing model: Overleaf is the source of truth

Editing happens in Overleaf; this git repo is the mirror. Two scripts drive the sync (both expect `pyoverleaf` on PATH and a project named `BranchingEquivalenceThesis`):

- `./sync-from-overleaf.sh` — downloads the Overleaf project, unzips into the working tree, then `git add . && git commit && git push`. Run this before starting local work so you don't edit a stale tree.
- `./sync-to-overleaf.sh` — `git pull`, then for each file changed in `HEAD~1..HEAD` uploads it back to Overleaf via `pyoverleaf write`. The case-statement in the script lists what is filtered out (`.git*`, `venv/`, `*.sh`, `README.md`, the sync scripts themselves) — extend that list rather than uploading new tooling files.

Practical consequence: never assume the working tree is the latest version. If the user asks for an edit and you don't know whether they have local Overleaf changes pending, ask before editing.

## Source layout (non-obvious bits)

- `tesis.tex` — top-level driver. Includes `caratula`, abstracts, agradecimientos, then `\include{capitulos/capituloN}` for chapters 1–5 + conclusiones + apéndice + bibliografía. To suppress optional front matter (English abstract, dedicatoria) comment the corresponding `\input` near lines 82–88.
- `commands.tex` — **all custom macros live here**. Before introducing a new command (`\D`, `\B`, `\MTSA`, `\step`, `\walk`, `\Goals`, etc.), grep this file — most domain notation is already defined. Two LaTeX-specific traps:
  - The file does `\let\D\undefined` / `\let\B\undefined` in `tesis.tex` lines 58–59 because it redefines those primitives; keep that pattern if you add macros that shadow built-ins.
  - There is a `showcomments` boolean (line 200) that toggles author-comment macros (`\ugh`, `\ins`, `\del`, `\chg`, `\nbc`, `\su`, `\dg`, `\np`, `\jb`). Setting it to `false` makes review markings disappear from the PDF without removing them from source.
- `tesis.cls` — custom thesis class (FCEN-style). Don't edit casually; layout choices flow from here.
- `capitulos/capituloN.tex` — the prose. Each chapter has a sibling `capN/` directory holding its figures (e.g. `capitulos/cap5/*.png`). Image paths in `\includegraphics` are relative to the project root.
- `capitulos/apendice/` and `capitulos/cap5/fng/` exist but contain only artifacts (`total`, etc.) — do not assume more chapters than `tesis.tex` includes.
- `versiones/BranchingEquivalence version {0,1,2}.java` — the three iterations of the algorithm being described in Chapter 5. They are study material; **don't try to compile them** (no MTSA classpath here) and don't refactor them. Treat them as read-only references.
- `Papers/jansen.pdf` — Jansen et al. 2019, the algorithm being implemented. Cite as such when discussing the pseudocode.

## Two planning docs you should read before non-trivial edits

These are NOT part of the thesis output but encode decisions already taken:

- `planificacion-tesis.md` — chapter-by-chapter outline, why the WSOE ≡ branching-bisimilarity proof gets its own Chapter 4, why writing started from Chapter 5, and a cuestionario whose bullet answers feed Chapter 5's prose.
- `versiones/comparacion-versiones.md` — exhaustive v0 → v1 → v2 diff (data structures, control flow, why the loop got refactored into two phases, which decisions are in the paper vs. invented for the DCS context). When the user asks "why does v1 use IdentityQueue?" or "what changed between v1 and v2?", the answer is in this file — read it before re-deriving.

If a thesis claim about the implementation contradicts these docs, the docs are the more recent source; flag the discrepancy rather than silently editing either side.

## Conventions to preserve

- Spanish prose, including in code comments inside `\begin{lstlisting}` blocks.
- Algorithm/code listings use the `pseudocode` `lstdefinelanguage` defined in `tesis.tex` lines 28–33 (keywords `if/then/else/for/while/...`); for Java snippets use `[language=Java]` as in `capitulos/capitulo5.tex` line 20.
- Author-comment macros (`\su{...}`, `\dg{...}`, etc.) are review-only; don't promote their content into running text without removing the macro.
- Custom symbols for the LTS/MTS domain (`\step`, `\walk`, `\hop`, `\runw`, `\enabled`, `\Goals`, `\Errors`, `\structure`, `\toOpen`, `\heuristic`, ...) are already defined in `commands.tex`; re-use them instead of inlining `\overset{...}{\rightarrow}` etc.

## What "done" means for an edit

Compile `tesis.tex` to PDF (the toolchain expectation is local pdflatex; if the user is editing on Overleaf, the equivalent is letting Overleaf recompile). Don't claim a change is done from inspecting the `.tex` alone — LaTeX errors only surface at compile time, and macro redefinitions in `commands.tex` can break chapters far from where you edited.
