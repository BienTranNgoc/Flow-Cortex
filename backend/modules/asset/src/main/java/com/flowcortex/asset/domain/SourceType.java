package com.flowcortex.asset.domain;

/** What a queue row registers: a completed run's outputs, or a started run's bound inputs. */
public enum SourceType {
    RUN_OUTPUTS("run_outputs"),
    RUN_INPUTS("run_inputs");

    private final String column;

    SourceType(String column) {
        this.column = column;
    }

    /** The value stored in {@code space_asset_registration.source_type}. */
    public String column() {
        return column;
    }

    public static SourceType fromColumn(String value) {
        for (SourceType type : values()) {
            if (type.column.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown registration source type: " + value);
    }
}
