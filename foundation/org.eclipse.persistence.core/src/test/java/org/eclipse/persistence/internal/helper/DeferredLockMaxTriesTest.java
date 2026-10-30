/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.eclipse.persistence.internal.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.persistence.exceptions.ConcurrencyException;
import org.eclipse.persistence.internal.identitymaps.CacheKey;
import org.eclipse.persistence.queries.ReadObjectQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CacheKey#acquireLock(org.eclipse.persistence.queries.ObjectBuildingQuery)} retries a deferred acquire
 * while another thread is still building the object and gives up with
 * {@link ConcurrencyException#maxTriesLockOnBuildObjectExceded}. The retries only run to exhaustion inside an
 * outer deferred build (at nesting depth 1 the loop's own {@code releaseDeferredLock()} waits for the owner
 * instead). Giving up must leave the thread's {@link DeferredLockManager} as it was before the call: an extra
 * nesting level makes every later outermost {@code releaseDeferredLock()} on this thread release nothing.
 * <p>
 * The exhaustion takes about 10 s (1000 retries of 10 ms, hard-coded in the loop).
 */
public class DeferredLockMaxTriesTest {

    private final CountDownLatch acquired = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private Thread owner;

    @AfterEach
    public void cleanUp() throws InterruptedException {
        finish.countDown();
        if (owner != null) {
            owner.join(TimeUnit.SECONDS.toMillis(5));
        }
        ConcurrencyManager.removeDeferredLockManager(Thread.currentThread());
    }

    @Test
    public void givingUpInsideAnOuterDeferredBuild_restoresTheOuterNestingLevel() throws InterruptedException {
        CacheKey outer = new CacheKey(2L);
        outer.acquireDeferredLock(); // this thread owns it: threadDepth 1, activeLocks [outer]
        DeferredLockManager lockManager = ConcurrencyManager.getDeferredLockManager(Thread.currentThread());
        assertNotNull(lockManager);
        assertEquals(1, lockManager.getThreadDepth());
        CacheKey key = heldByAnotherThreadWithoutObject(3L);

        ConcurrencyException exception = assertThrows(ConcurrencyException.class, () -> key.acquireLock(deferredQuery()));
        assertEquals(ConcurrencyException.MAX_TRIES_EXCEDED_FOR_LOCK_ON_BUILD_OBJECT, exception.getErrorCode());

        assertSame(lockManager, ConcurrencyManager.getDeferredLockManager(Thread.currentThread()));
        assertEquals(1, lockManager.getThreadDepth(), "the failed retry's nesting level was not undone");
        assertFalse(lockManager.getDeferredLocks().contains(key), "the failed retry left a stale deferred entry");
        assertTrue(key.isAcquired());
        assertSame(owner, key.getActiveThread());

        outer.releaseDeferredLock(); // outermost: must release outer and drop the manager
        assertFalse(outer.isAcquired());
        assertNull(ConcurrencyManager.getDeferredLockManager(Thread.currentThread()));
    }

    private CacheKey heldByAnotherThreadWithoutObject(Object primaryKey) throws InterruptedException {
        CacheKey key = new CacheKey(primaryKey);
        owner = new Thread(() -> {
            key.acquire();
            acquired.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            key.release();
        }, "deferred-lock-max-tries-owner");
        owner.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        assertNull(key.getObject());
        return key;
    }

    private static ReadObjectQuery deferredQuery() {
        ReadObjectQuery query = new ReadObjectQuery();
        query.setRequiresDeferredLocks(true);
        return query;
    }
}
