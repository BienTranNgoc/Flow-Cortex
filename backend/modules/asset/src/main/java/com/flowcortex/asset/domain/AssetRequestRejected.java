package com.flowcortex.asset.domain;

/** A library request refused as invalid; names the request parameter the member must change. */
public class AssetRequestRejected extends RuntimeException {

    private final String parameter;

    public AssetRequestRejected(String parameter) {
        super("Invalid request parameter: " + parameter);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
