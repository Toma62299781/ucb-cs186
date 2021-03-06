package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have what locks
 * on what resources and handles queuing logic. The lock manager should generally
 * NOT be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with multiple
 * levels of granularity. Multigranularity is handled by LockContext instead.
 *
 * Each resource the lock manager manages has its own queue of LockRequest
 * objects representing a request to acquire (or promote/acquire-and-release) a
 * lock that could not be satisfied at the time. This queue should be processed
 * every time a lock on that resource gets released, starting from the first
 * request, and going in order until a request cannot be satisfied. Requests
 * taken off the queue should be treated as if that transaction had made the
 * request right after the resource was released in absence of a queue (i.e.
 * removing a request by T1 to acquire X(db) should be treated as if T1 had just
 * requested X(db) and there were no queue on db: T1 should be given the X lock
 * on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is
 * processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();

    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently granted locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // Below are a list of helper methods we suggest you implement.
        // You're free to modify their type signatures, delete, or ignore them.

        /**
         * Check if `lockType` is compatible with preexisting locks. Allows
         * conflicts for locks held by transaction with id `except`, which is
         * useful when a transaction tries to replace a lock it already has on
         * the resource. Pass -1 to except if there is no exception.
         */
        public boolean checkCompatible(LockType lockType, long except) {
            // (proj4_part1): implement
            for (Lock lock : locks) {
                if (lock.transactionNum != except && !LockType.compatible(lock.lockType, lockType)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Gives the transaction the lock `lock`. Assumes that the lock is
         * compatible. Updates lock on resource if the transaction already has a
         * lock.
         */
        public void grantOrUpdateLock(Lock lock) {
            // (proj4_part1): implement
            // the tricky part here is that we need to update
            // both 1. ResourceEntry::locks and
            // 2. LockManager::transactionLocks
            long transactionNum = lock.transactionNum;
            // if no lock on this resource, grant the lock
            if (getTransactionLockType(transactionNum) == LockType.NL) {
                transactionLocks.putIfAbsent(transactionNum, new ArrayList<>());
                transactionLocks.get(transactionNum).add(lock);

                // update locks on this ResourceEntry
                locks.add(lock);
            } else {
                // update the lock
                for (Lock lockOnCurrTxn : transactionLocks.get(transactionNum)) {
                    // update lock holds by the given transaction
                    if (lockOnCurrTxn.name.equals(lock.name)) {
                        lockOnCurrTxn.lockType = lock.lockType;
                    }
                }

                for (Lock lockOnCurrResource : locks) {
                    // update the lock on this resource
                    if (lockOnCurrResource.name.equals(lock.name)) {
                        lockOnCurrResource.lockType = lock.lockType;
                    }
                }
            }
        }

        /**
         * Check if there is LockRequest waiting on `name`.
         * Self-added helper method.
         */
        public boolean waitingForResource(ResourceName name) {
            for (LockRequest lockRequest : waitingQueue) {
                if (lockRequest.lock.name.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Releases the lock `lock` and processes the queue. Assumes that the
         * lock has been granted before.
         */
        public void releaseLock(Lock lock) {
            // (proj4_part1): implement
            // releasing the lock `lock` means remove it from
            // 1. the list `locks` of ResourceEntry
            // 2. the List<Lock> of transactionLocks
            // then process the Queue
            locks.remove(lock);
            transactionLocks.get(lock.transactionNum).remove(lock);
            processQueue();
        }

        /**
         * Adds `request` to the front of the queue if addFront is true, or to
         * the end otherwise.
         */
        public void addToQueue(LockRequest request, boolean addFront) {
            // (proj4_part1): implement
            if (addFront) {
                waitingQueue.addFirst(request);
            } else {
                waitingQueue.addLast(request);
            }
        }

        /**
         * Grant locks to requests from front to back of the queue, stopping
         * when the next lock cannot be granted. Once a request is completely
         * granted, the transaction that made the request can be unblocked.
         * The request at the front of the queue is considered, and if it doesn't conflict with any of the existing
         * locks on the resource, it should be removed from the queue and:
         *      1. the transaction that made the request should be given the lock
         *      2. any locks that the request stated should be released are released
         *      3. the transaction that made the request should be unblocked
         * The previous step should be repeated until the first request on the queue cannot be satisfied
         * or the queue is empty.
         */
        private void processQueue() {
            Iterator<LockRequest> requests = waitingQueue.iterator();

            // (proj4_part1): implement
            while (requests.hasNext()) {
                LockRequest request = requests.next();
                Lock lock = request.lock;
                long txNum = request.transaction.getTransNum();

                if (checkCompatible(lock.lockType, txNum)) {
                    // grant or update the lock if compatible
                    grantOrUpdateLock(lock);

                    // the first request in the queue has been processed, remove it
                    requests.remove();

                    // unblock the corresponding transaction
                    request.transaction.unblock();
                } else {
                    break; // stop
                }
            }
        }

        /**
         * Gets the type of lock `transaction` has on this resource.
         */
        public LockType getTransactionLockType(long transaction) {
            // (proj4_part1): implement
            if (locks.isEmpty()) {
                // no locks on this resource, return LockType.NL
                return LockType.NL;
            }

            List<Lock> locksHoldByTransaction = transactionLocks.get(transaction);
            if (locksHoldByTransaction == null || locksHoldByTransaction.isEmpty()) {
                // the transaction holds no lock, return LockType.NL
                return LockType.NL;
            }
            ResourceName resourceName = locks.get(0).name;
            for (Lock lock : locksHoldByTransaction) {
                if (lock.name.equals(resourceName)) {
                    return lock.lockType;
                }
            }

            return LockType.NL;
        }

        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                    ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<String, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to `name`.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    /** Self-added helper method
     * Helper method to get the lock held by the transaction on a resource.
     * @return null if no such lock held.
     */
    private Lock getLock(long transactionNum, ResourceName name) {
        List<Lock> txLocks = transactionLocks.get(transactionNum);
        for (Lock lock : txLocks) {
            if (lock.name.equals(name)) {
                return lock;
            }
        }
        return null;
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`, and
     * releases all locks on `releaseNames` held by the transaction after
     * acquiring the lock in one atomic action.
     *
     * Error checking must be done before any locks are acquired or released. If
     * the new lock is not compatible with another transaction's lock on the
     * resource, the transaction is blocked and the request is placed at the
     * FRONT of the resource's queue.
     *
     * Locks on `releaseNames` should be released only after the requested lock
     * has been acquired. The corresponding queues should be processed.
     *
     * An acquire-and-release that releases an old lock on `name` should NOT
     * change the acquisition time of the lock on `name`, i.e. if a transaction
     * acquired locks in the order: S(A), X(B), acquire X(A) and release S(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is already held
     * by `transaction` and isn't being released
     * @throws NoLockHeldException if `transaction` doesn't hold a lock on one
     * or more of the names in `releaseNames`
     */
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseNames)
            throws DuplicateLockRequestException, NoLockHeldException {
        // (proj4_part1): implement
        // You may modify any part of this method. You are not required to keep
        // all your code within the given synchronized block and are allowed to
        // move the synchronized block elsewhere if you wish.
        boolean shouldBlock = false;
        // we should not call synchronized methods outside the synchronized block below, since calling it outside the
        // synchronized block will fall into the multiple synchronized block case
        // introduced in (https://cs186.gitbook.io/project/assignments/proj4/part-1-lockmanager#synchronization)
        synchronized (this) {
            long txNum = transaction.getTransNum();
            ResourceEntry resourceEntry = getResourceEntry(name);

            if (getLockType(transaction, name) != LockType.NL && !releaseNames.contains(name)) {
                throw new DuplicateLockRequestException("Duplicate lock request from transaction " + txNum + " on " + name);
            }

            for (ResourceName resourceName : releaseNames) {
                // check for NoLockHeldException
                if (getLockType(transaction, resourceName) == LockType.NL) {
                    throw new NoLockHeldException("No lock held by " + txNum + " for " + name.toString());
                }
            }

            shouldBlock = !resourceEntry.checkCompatible(lockType, txNum); // except the current transaction
            Lock lockToAcquire = new Lock(name, lockType, txNum);
            if (!shouldBlock) {
                // release
                for (ResourceName resourceName : releaseNames) {
                    getResourceEntry(resourceName).releaseLock(getLock(txNum, resourceName));
                }
                // grant the lock
                resourceEntry.grantOrUpdateLock(lockToAcquire);
            } else {
                LockRequest lockRequest = new LockRequest(transaction, lockToAcquire);
                resourceEntry.waitingQueue.addLast(lockRequest);

                transaction.prepareBlock();
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`.
     *
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is held by
     * `transaction`
     */
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        // (proj4_part1): implement
        // You may modify any part of this method. You are not required to keep all your
        // code within the given synchronized block and are allowed to move the
        // synchronized block elsewhere if you wish.
        boolean shouldBlock = false;
        synchronized (this) {
            long txNum = transaction.getTransNum();
            ResourceEntry resourceEntry = getResourceEntry(name);

            if (!getLockType(transaction, name).equals(LockType.NL)) {
                throw new DuplicateLockRequestException("Duplicate lock request from " + txNum +
                        " on " + name.toString());
            }

            shouldBlock = !resourceEntry.checkCompatible(lockType, -1); // pass -1, no exception

            // check if there are other transactions waiting in the queue for this resource
            if (!shouldBlock) {
                shouldBlock = resourceEntry.waitingForResource(name);
            }
            Lock lockToAcquire = new Lock(name, lockType, txNum);
            if (!shouldBlock) {
                resourceEntry.grantOrUpdateLock(lockToAcquire);
            } else {
                // create a new LockRequest and place it at the end of the waitingQueue of name
                LockRequest lockRequest = new LockRequest(transaction, lockToAcquire);
                resourceEntry.waitingQueue.addLast(lockRequest);

                // call prepareBlock to avoid race condition
                transaction.prepareBlock();
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Release `transaction`'s lock on `name`. Error checking must be done
     * before the lock is released.
     *
     * The resource name's queue should be processed after this call. If any
     * requests in the queue have locks to be released, those should be
     * released, and the corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     */
    public void release(TransactionContext transaction, ResourceName name)
            throws NoLockHeldException {
        // (proj4_part1): implement
        // You may modify any part of this method.
        synchronized (this) {
            if (getLockType(transaction, name) == LockType.NL) {
                // error checking
                throw new NoLockHeldException("No lock on" +  name.toString()+  " is held by" +
                        transaction.getTransNum() +  " the given transaction");
            }

            // release `transactions`'s lock on `name`:
            getResourceEntry(name).releaseLock(getLock(transaction.getTransNum(), name));
        }
    }

    /**
     * Promote a transaction's lock on `name` to `newLockType` (i.e. change
     * the transaction's lock on `name` from the current lock type to
     * `newLockType`, if its a valid substitution).
     *
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the
     * transaction is blocked and the request is placed at the FRONT of the
     * resource's queue.
     *
     * A lock promotion should NOT change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock on `name`
     * @throws NoLockHeldException if `transaction` has no lock on `name`
     * @throws InvalidLockException if the requested lock type is not a
     * promotion. A promotion from lock type A to lock type B is valid if and
     * only if B is substitutable for A, and B is not equal to A.
     */
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // (proj4_part1): implement
        // You may modify any part of this method.
        boolean shouldBlock = false;
        synchronized (this) {
            long txNum = transaction.getTransNum();
            ResourceEntry resourceEntry = getResourceEntry(name);
            LockType oldLockType = getLockType(transaction, name);

            if (oldLockType.equals(newLockType)) {
                // check whether the `transaction` already has a `newLockType` lock on `name`
                throw new DuplicateLockRequestException("Duplicate lock request from " + txNum +
                        " on " + name.toString());
            }

            if (oldLockType.equals(LockType.NL)) {
                // the `transaction` has no lock on `name`
                throw new NoLockHeldException("Transaction " + txNum + " does not hold a lock on " + name.toString());
            }

            if (!LockType.substitutable(newLockType, oldLockType)) {
                throw new InvalidLockException("Could not promote from " + oldLockType + " to " + newLockType);
            }

            shouldBlock = !resourceEntry.checkCompatible(newLockType, txNum); // except the old lock
            Lock lockToAcquire = new Lock(name, newLockType, txNum);
            if (!shouldBlock) {
                // grant the lock
                resourceEntry.grantOrUpdateLock(lockToAcquire);
            } else {
                // put at the front of the waiting queue of the resource
                LockRequest lockRequest = new LockRequest(transaction, lockToAcquire);
                resourceEntry.waitingQueue.addFirst(lockRequest);

                // call prepareBlock to avoid race condition
                transaction.prepareBlock();
            }
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Return the type of lock `transaction` has on `name` or NL if no lock is
     * held.
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
        // (proj4_part1): implement
        ResourceEntry resourceEntry = getResourceEntry(name);
        long txNum = transaction.getTransNum();
        for (Lock lock : resourceEntry.locks) {
            if (lock.transactionNum == txNum) {
                return lock.lockType;
            }
        }
        return LockType.NL;
    }

    /**
     * Returns the list of locks held on `name`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks held by `transaction`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at the top of this file and the top
     * of LockContext.java for more information.
     */
    public synchronized LockContext context(String name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, name));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at the top of this
     * file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database");
    }
}
