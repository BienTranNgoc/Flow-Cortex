# Fonts in the media-runtime image

The `media-runtime` stage of `worker/Dockerfile` carries two font sets. Both allow redistribution
inside the image; the text of each licence ships with the files.

## Debian packages

Read from `/usr/share/doc/<package>/copyright` in the image built from `python:3.12-slim`
(Debian 13.7).

| package | version | licence | copyright file |
|---|---|---|---|
| `fonts-dejavu-core` | 2.37-8 | Bitstream Vera licence (DejaVu changes in the public domain) | `/usr/share/doc/fonts-dejavu-core/copyright` |
| `fonts-montserrat` | 7.222-3 | SIL Open Font License 1.1 (packaging CC0-1) | `/usr/share/doc/fonts-montserrat/copyright` |
| `fonts-open-sans` | 1.11-2 | Apache License 2.0 | `/usr/share/doc/fonts-open-sans/copyright` |
| `fonts-quicksand` | 0.2016-2.1 | SIL Open Font License 1.1 | `/usr/share/doc/fonts-quicksand/copyright` |

## Vendored families (`worker/fonts/`)

28 families, installed to `/usr/share/fonts/truetype/flowcortex/`, each under the SIL Open Font
License 1.1 with its unmodified `OFL.txt` beside the font files:

Be Vietnam Pro, Roboto, Inter, Nunito, Mulish, Barlow, Oswald, Anton, Bungee, Paytone One,
Chakra Petch, Pacifico, Lobster, Charm, Patrick Hand, Itim, Mali, Pangolin, Sriracha, Baloo 2,
Tilt Neon, Neonderthaw, Grenze Gotisch, Sedgwick Ave Display, Bangers, Bungee Inline,
Bungee Shade, Kablammo.

The TTF files are static instances; for the families cut from a variable font this is a
modification under the same licence, with the licence text kept.

Two families declare a Reserved Font Name and are not cut from a variable font, so they keep
their names: Lobster ("Lobster") and Paytone One ("Paytone", "Paytone One").

Six families that declare a Reserved Font Name and would have been cut from variable fonts are
not vendored: Dancing Script, Josefin Sans, Lexend, Lora, Merriweather, Playfair Display.
