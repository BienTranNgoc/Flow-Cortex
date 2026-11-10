/**
 * Mail module: the closed catalog of mail keys and the one transport every module sends through.
 * Other modules send via its {@code api} and supply recipient addresses through
 * {@code MailRecipientDirectory}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Mail",
        allowedDependencies = {
                "shared :: web"
        }
)
package com.flowcortex.mail;
