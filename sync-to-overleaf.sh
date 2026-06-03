#!/bin/bash
set -e

PROJECT="BranchingEquivalenceThesis"

echo "→ Trayendo últimos cambios de GitHub..."
git pull

echo "→ Detectando archivos modificados..."
# Archivos cambiados en el último commit (respecto al anterior)
FILES=$(git diff --name-only HEAD~1 HEAD 2>/dev/null || git ls-files)

if [ -z "$FILES" ]; then
  echo "✓ No hay archivos para subir"
  exit 0
fi

echo "→ Subiendo a Overleaf..."
while IFS= read -r file; do
  # Saltar archivos que no deberían ir a Overleaf
  case "$file" in
    .git*|venv/*|versiones/*|docs/*|*.sh|README.md|sync-from-overleaf.sh|sync-to-overleaf.sh)
      echo "  - skip: $file"
      continue
      ;;
  esac

  if [ -f "$file" ]; then
    echo "  ↑ $file"
    cat "$file" | pyoverleaf write "$PROJECT/$file"
  fi
done <<< "$FILES"

echo "✓ Todo subido a Overleaf"
