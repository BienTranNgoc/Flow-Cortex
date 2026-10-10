#!/usr/bin/env bash
# Regenerates the pydantic contract models from contracts/*.schema.json.
# Output is deterministic (no timestamp), so CI can diff it against the commit.
set -euo pipefail

cd "$(dirname "$0")/.."
generate() {
    local pattern=$1
    local out=$2
    local schemas=(../contracts/${pattern}-*.schema.json)
    if [[ ! -e "${schemas[0]}" ]]; then
        echo "gen_contracts: no ../contracts/${pattern}-*.schema.json found" >&2
        exit 1
    fi
    mkdir -p "${out}"
    for schema in "${schemas[@]}"; do
    name=$(basename "${schema}" .schema.json)
    uv run --frozen datamodel-codegen \
        --input "${schema}" \
        --input-file-type jsonschema \
        --output "${out}/${name//-/_}.py" \
        --output-model-type pydantic_v2.BaseModel \
        --target-python-version 3.12 \
        --output-datetime-class AwareDatetime \
        --strict-types str int bool \
        --field-constraints \
        --use-annotated \
        --use-standard-collections \
        --use-union-operator \
        --enum-field-as-literal all \
        --use-title-as-name \
        --collapse-root-models \
        --use-schema-description \
        --disable-timestamp \
        --formatters ruff-format
    done
}

generate media src/flowcortex_worker/contracts
generate provider src/flowcortex_worker/provider/contracts
