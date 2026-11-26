/**
 * Public API of the trash module: the port an owning module records its soft deletes through, and the
 * provider it implements to let the Trash restore and purge its rows.
 */
@org.springframework.modulith.NamedInterface("api")
package com.flowcortex.trash.api;
