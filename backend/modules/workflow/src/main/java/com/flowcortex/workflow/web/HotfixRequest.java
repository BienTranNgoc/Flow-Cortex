package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Size;

/** The optional note of a hotfix version. */
record HotfixRequest(@Size(max = 200) String note) {
}
