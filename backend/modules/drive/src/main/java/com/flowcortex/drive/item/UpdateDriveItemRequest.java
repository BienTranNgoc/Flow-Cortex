package com.flowcortex.drive.item;

import com.flowcortex.drive.item.DriveItemService.ItemPatch;

/** Patch body: an absent field stays as it is; content type, file and run output never change. */
record UpdateDriveItemRequest(String name, String semanticRole, String textValue) {

    ItemPatch toPatch() {
        return new ItemPatch(name, semanticRole, textValue);
    }
}
