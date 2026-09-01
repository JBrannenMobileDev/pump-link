#!/usr/bin/env bash
#
# Renders every diagrams/*.puml to docs/img/<name>-light.svg and <name>-dark.svg.
#
# Diagram sources include "style.puml" without knowing which one they will get;
# this script stages the chosen palette under that name before invoking
# PlantUML, which keeps the sources free of theme conditionals.
#
#   tools/render-diagrams.sh            render and write SVGs
#   tools/render-diagrams.sh --check    fail if committed SVGs are stale
#
# PlantUML is pinned. A byte-comparison gate against releases/latest is not a
# real gate: GitHub's latest tag can move to a snapshot between a local render
# and a CI run, and every SVG then "fails" without the sources changing.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/diagrams"
OUT="$ROOT/docs/img"
TOOLS="$ROOT/.tools"
PLANTUML_VERSION="1.2026.7"
JAR="$TOOLS/plantuml-${PLANTUML_VERSION}.jar"
PLANTUML_URL="https://github.com/plantuml/plantuml/releases/download/v${PLANTUML_VERSION}/plantuml.jar"

CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

command -v java >/dev/null || { echo "java not found. Set JAVA_HOME." >&2; exit 1; }
command -v dot  >/dev/null || echo "warning: graphviz 'dot' not found; state and component layout may fail" >&2

mkdir -p "$TOOLS"
if [[ ! -f "$JAR" ]]; then
  echo "fetching PlantUML ${PLANTUML_VERSION}"
  curl -sL -o "$JAR" "$PLANTUML_URL"
fi

dest="$OUT"
if [[ $CHECK -eq 1 ]]; then
  dest="$(mktemp -d)"
  trap 'rm -rf "$dest"' EXIT
fi
mkdir -p "$dest"

for theme in light dark; do
  stage="$TOOLS/stage-$theme"
  rm -rf "$stage"; mkdir -p "$stage"

  cp "$SRC/style-$theme.puml" "$stage/style.puml"
  for f in "$SRC"/*.puml; do
    [[ "$(basename "$f")" == style-*.puml ]] && continue
    cp "$f" "$stage/"
  done

  # -nometadata keeps output byte-stable so --check diffs mean something.
  java -jar "$JAR" -tsvg -nometadata -failfast2 -o "$stage/out" "$stage/*.puml" >/dev/null

  for svg in "$stage"/out/*.svg; do
    name="$(basename "$svg" .svg)"
    [[ "$name" == "style" ]] && continue
    mv "$svg" "$dest/$name-$theme.svg"
  done
  rm -rf "$stage"
done

if [[ $CHECK -eq 1 ]]; then
  # Compare rendered SVGs only. docs/img/states/ is a screenshot tree and
  # must not fail the diagram-staleness gate.
  status=0
  for rendered in "$dest"/*.svg; do
    name="$(basename "$rendered")"
    if ! cmp -s "$rendered" "$OUT/$name"; then
      echo "stale: $name" >&2
      status=1
    fi
  done
  for committed in "$OUT"/*.svg; do
    name="$(basename "$committed")"
    if [[ ! -f "$dest/$name" ]]; then
      echo "orphan: $name has no source" >&2
      status=1
    fi
  done
  if [[ $status -ne 0 ]]; then
    echo "committed SVGs are stale. Run tools/render-diagrams.sh and commit." >&2
    exit 1
  fi
  echo "diagrams up to date ($(ls -1 "$OUT"/*.svg | wc -l | tr -d ' ') files)"
else
  echo "rendered $(ls -1 "$OUT"/*.svg | wc -l | tr -d ' ') files to docs/img"
fi
