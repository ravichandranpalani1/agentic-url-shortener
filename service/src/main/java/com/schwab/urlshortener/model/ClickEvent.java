package com.schwab.urlshortener.model;

/** An immutable record of a single redirect/click, used for analytics. */
public record ClickEvent(String code, long timestampMillis, String referrer, String userAgent, String clientHash) {
}
