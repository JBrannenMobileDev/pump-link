# Bundled fonts

Liberation Sans and Liberation Mono. PlantUML measures glyph widths when it
writes SVG `textLength` attributes, so a host-only font (Helvetica Neue on
macOS, DejaVu on Ubuntu) makes `--check` fail even when the `.puml` is
unchanged. These files travel with the repo so both sides use the same
metrics.

License: [LICENSE](LICENSE) (SIL Open Font License).
