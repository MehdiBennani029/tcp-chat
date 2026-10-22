package com.chat.client.view;

/** One row of the message list. */
record ChatLine(Kind kind, String text) {

    enum Kind { OWN, OTHER, INFO }
}
