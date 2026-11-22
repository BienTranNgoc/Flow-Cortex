package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Size;

/** The optional name of a saved or restored version; absent or null leaves it unnamed. */
record SaveVersionRequest(@Size(max = 200) String changelog) {
}
