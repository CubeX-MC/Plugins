package org.cubexmc.booklite.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BookRecord {

    private final String id;
    private final String contentHash;
    private final String title;
    private final String author;
    private final List<String> pages;
    private final long createdAt;
    private long updatedAt;
    private Long lastAccessedAt;

    public BookRecord(String id, String contentHash, String title, String author,
                      List<String> pages, long createdAt, long updatedAt) {
        this(id, contentHash, title, author, pages, createdAt, updatedAt, null);
    }

    public BookRecord(String id, String contentHash, String title, String author,
                      List<String> pages, long createdAt, long updatedAt,
                      Long lastAccessedAt) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
        this.contentHash = Objects.requireNonNull(contentHash);
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages == null ? List.of("") : pages));
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    public String id() { return id; }
    public String contentHash() { return contentHash; }
    public String title() { return title; }
    public String author() { return author; }
    public List<String> pages() { return pages; }
    public int totalPages() { return pages.size(); }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public Long lastAccessedAt() { return lastAccessedAt; }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setLastAccessedAt(Long lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
