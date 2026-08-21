#!/usr/bin/env bash
# Print one release's section of CHANGELOG.md, without its heading.
#
#   bin/changelog-section.sh 0.1.4     the 0.1.4 section
#   bin/changelog-section.sh           the topmost section
#
# Both the GitHub release notes and the Nexus file description are produced from
# this, so a release says the same thing in both places and neither is written by
# hand at tag time.
#
# Exits non-zero when the requested version has no section. That is deliberate:
# a release whose notes are silently empty is worse than one that fails to build,
# because the empty one ships.
set -euo pipefail

version="${1:-}"
file="${CHANGELOG:-CHANGELOG.md}"

[ -f "$file" ] || { echo "no $file" >&2; exit 1; }

if [ -n "$version" ]; then
  # tolerate a leading v on the tag
  version="${version#v}"
  start=$(grep -n "^## ${version}\b" "$file" | head -1 | cut -d: -f1) || true
  [ -n "${start:-}" ] || { echo "no section for $version in $file" >&2; exit 1; }
else
  start=$(grep -n '^## ' "$file" | head -1 | cut -d: -f1) || true
  [ -n "${start:-}" ] || { echo "no sections in $file" >&2; exit 1; }
fi

# from the line after the heading, up to the line before the next '## '
rest=$((start + 1))
len=$(tail -n "+$rest" "$file" | grep -n '^## ' | head -1 | cut -d: -f1) || true
if [ -n "${len:-}" ]; then
  body=$(tail -n "+$rest" "$file" | head -n "$((len - 1))")
else
  body=$(tail -n "+$rest" "$file")
fi

# trim blank lines at both ends
printf '%s\n' "$body" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac
