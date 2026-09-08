package com.schwab.urlshortener;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.AliasConflictException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.InMemoryUrlStore;
import com.schwab.urlshortener.store.UrlStore;
import com.schwab.urlshortener.store.WriteAheadLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryUrlStoreTest {

    private UrlStore freshStore() throws IOException {
        Path dir = Files.createTempDirectory("uss-test-");
        return new InMemoryUrlStore(new WriteAheadLog(dir.resolve("wal.log")));
    }

    @Test
    void createGeneratesAUniqueSequentialCode() throws IOException {
        UrlStore store = freshStore();
        UrlRecord a = store.create("https://a.example.com", null, null);
        UrlRecord b = store.create("https://b.example.com", null, null);
        assertFalse(a.code().equals(b.code()), "sequential creates must not collide");
        assertFalse(a.customAlias(), "generated code should not be flagged as a custom alias");
    }

    @Test
    void createWithCustomAliasUsesThatExactCode() throws IOException {
        UrlStore store = freshStore();
        UrlRecord r = store.create("https://schwab.com/research", "research", null);
        assertEquals("research", r.code(), "custom alias should be used verbatim as the code");
        assertTrue(r.customAlias(), "customAlias flag should be true");
    }

    @Test
    void duplicateCustomAliasThrowsConflict() throws IOException {
        UrlStore store = freshStore();
        store.create("https://a.example.com", "dup-alias", null);
        assertThrows(AliasConflictException.class,
                () -> store.create("https://b.example.com", "dup-alias", null),
                "second create with the same alias must be rejected");
    }

    @Test
    void findReturnsEmptyForUnknownCode() throws IOException {
        UrlStore store = freshStore();
        assertEquals(Optional.empty(), store.find("nope"), "unknown code should not be found");
    }

    @Test
    void softDeleteDeactivatesButKeepsTheRecordQueryable() throws IOException {
        UrlStore store = freshStore();
        UrlRecord r = store.create("https://a.example.com", "todelete", null);
        assertTrue(store.softDelete("todelete"), "delete of an existing code should succeed");
        UrlRecord after = store.find("todelete").orElseThrow();
        assertFalse(after.active(), "record should be inactive after soft delete");
        assertEquals(r.code(), after.code(), "soft delete must not remove the record, only deactivate it");
    }

    @Test
    void softDeleteOfUnknownCodeReturnsFalse() throws IOException {
        UrlStore store = freshStore();
        assertFalse(store.softDelete("does-not-exist"), "deleting an unknown code should return false, not throw");
    }

    @Test
    void recordClickIncrementsCounterAndRecentList() throws IOException {
        UrlStore store = freshStore();
        store.create("https://a.example.com", "clicky", null);
        store.recordClick(new ClickEvent("clicky", System.currentTimeMillis(), "https://ref", "ua", "hash1"));
        store.recordClick(new ClickEvent("clicky", System.currentTimeMillis(), null, "ua", "hash2"));

        UrlRecord after = store.find("clicky").orElseThrow();
        assertEquals(2L, after.clickCount(), "click count should reflect both recorded clicks");

        List<ClickEvent> recent = store.recentClicks("clicky", 10);
        assertEquals(2, recent.size(), "both clicks should appear in recent clicks");
    }

    @Test
    void expiredRecordIsReportedAsExpired() throws IOException {
        UrlStore store = freshStore();
        UrlRecord r = store.create("https://a.example.com", "expiring", 1L); // 1 second TTL
        assertFalse(r.isExpired(System.currentTimeMillis()), "should not be expired immediately");
        assertTrue(r.isExpired(System.currentTimeMillis() + 2000), "should be expired 2s after a 1s TTL");
    }

    @Test
    void writeAheadLogSurvivesStoreRestart() throws IOException {
        Path dir = Files.createTempDirectory("uss-test-wal-");
        Path walPath = dir.resolve("wal.log");

        UrlStore first = new InMemoryUrlStore(new WriteAheadLog(walPath));
        first.create("https://a.example.com", "durable", null);
        first.recordClick(new ClickEvent("durable", System.currentTimeMillis(), null, "ua", "h"));
        first.softDelete("durable");

        // Simulate a process restart: build a brand new store instance against the same WAL file.
        UrlStore recovered = new InMemoryUrlStore(new WriteAheadLog(walPath));
        UrlRecord r = recovered.find("durable").orElseThrow();
        assertEquals(1L, r.clickCount(), "click count must survive a restart via WAL replay");
        assertFalse(r.active(), "soft delete must survive a restart via WAL replay");
    }
}
