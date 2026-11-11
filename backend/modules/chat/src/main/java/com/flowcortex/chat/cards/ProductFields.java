package com.flowcortex.chat.cards;

/** The Product Video fields of a card, as the member sees and edits them. */
public record ProductFields(String productName, String description, String cta, String price, String promotion) {
}
