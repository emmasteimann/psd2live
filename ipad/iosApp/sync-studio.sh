#!/bin/zsh
# Copies the studio's current build into the iPad app (iosApp/studio). Run after changing the studio.
set -e
STUDIO="$HOME/Downloads/neuro-goose/layerloom"
HERE="${0:A:h}"
(cd "$STUDIO" && npx vite build >/dev/null)
rm -rf "$HERE/studio"
cp -R "$STUDIO/dist/client" "$HERE/studio"
echo "Studio copied into $HERE/studio"
