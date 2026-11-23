"""Engine children: run as `python -m flowcortex_worker.engines.<name>`, never imported by the
worker process, so the heavy libraries load only inside them."""

# Exit code of an engine child whose library or model cannot be loaded.
EXIT_UNLOADABLE = 3
