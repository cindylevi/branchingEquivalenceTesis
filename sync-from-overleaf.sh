#!/bin/bash
set -e

PROJECT="BranchingEquivalenceThesis"

echo "→ Bajando de Overleaf..."
pyoverleaf download-project "$PROJECT" _tmp.zip
unzip -o _tmp.zip
rm _tmp.zip

echo "→ Commit y push..."
git add .
if git diff --cached --quiet; then
  echo "✓ No hay cambios nuevos"
else
  git commit -m "sync desde overleaf $(date +%Y-%m-%d_%H:%M)"
  git push
  echo "✓ Todo sincronizado"
fi
