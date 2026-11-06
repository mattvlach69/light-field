#!/bin/bash
# Copies Field recordings and notes from the Light Phone to a "Field" folder on your Desktop.
# Needs: phone plugged in, USB debugging on, adb installed. Run with: bash get-recordings.command
PKG=com.matthewvlach.field
OUT="$HOME/Desktop/Field"
mkdir -p "$OUT"
adb exec-out run-as $PKG tar -cf - -C files/shared recordings notes 2>/dev/null | tar -xf - -C "$OUT" \
  || { echo "Couldn't reach Field. Is the phone plugged in and unlocked?"; read -r; exit 1; }
echo "Copied to $OUT"
open "$OUT"
read -r -p "Press Enter to close."
