rootProject.name = "flow-cortex"

// Single module for now. When a second bounded context appears, promote this to
// a multi-module build the way the reference repo does: extract the shared build
// logic into buildSrc convention plugins and `include(":modules:...")` here.
