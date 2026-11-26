package com.flowcortex.trash.domain;

import java.util.regex.Pattern;

/** The formats of the Trash's identifiers, the same as the table's checks. */
public final class TrashKeys {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LOCATION_CODE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private TrashKeys() {
    }

    public static boolean validType(String type) {
        return type != null && TYPE.matcher(type).matches();
    }

    public static boolean validItemId(String itemId) {
        return itemId != null && ITEM_ID.matcher(itemId).matches();
    }

    public static boolean validLocationCode(String code) {
        return code != null && LOCATION_CODE.matcher(code).matches();
    }
}
