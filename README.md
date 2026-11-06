# Field for Light Phone III

A field recorder and notes tool. Records 48 kHz mono WAV from the phone's unprocessed mic (no auto gain or noise suppression), keeps recording with the screen locked, lets you drop marks while recording, and keeps a note with each take. Plain text notes live alongside recordings.

Install: download `field.apk` from the [latest release](../../releases/tag/latest) and drop it into Light Phone Manager.

Get recordings onto your Mac: `bash get-recordings.command` (copies to ~/Desktop/Field).

Background recording needs a small addition to the Light SDK (`sdk/client/.../field`), so this tool builds from this repo rather than Light's tool builder.
