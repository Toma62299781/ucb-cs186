package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.cli.parser.ParseException;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.databox.Type;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // (proj5): implement

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        CommitTransactionLogRecord commitRecord = new CommitTransactionLogRecord(transNum, prevLSN);
        long LSN = logManager.appendToLog(commitRecord);

        logManager.flushToLSN(LSN); // flush the log
        transactionEntry.lastLSN = LSN;
        transactionEntry.transaction.setStatus(Transaction.Status.COMMITTING);
        return LSN;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // (proj5): implement
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        AbortTransactionLogRecord abortRecord = new AbortTransactionLogRecord(transNum, prevLSN);
        // append to log and get the abortLSN
        long LSN = logManager.appendToLog(abortRecord);
        // update the transactionTable on lastLSN
        transactionEntry.lastLSN = LSN;
        // set the status of the given transaction to ABORTING
        transactionEntry.transaction.setStatus(Transaction.Status.ABORTING);
        return LSN;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // (proj5): implement
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionTable.get(transNum).lastLSN;
        if (transactionEntry.transaction.getStatus() == Transaction.Status.ABORTING) {
            // roll back to the first record of this transaction?
            prevLSN = rollbackToLSN(transNum, 0);
        }

        transactionTable.remove(transNum);
        transactionEntry.transaction.setStatus(Transaction.Status.COMPLETE);

        long LSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, prevLSN));
        return LSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Emit the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private long rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        // (proj5) implement the rollback logic described above
        while (currentLSN > LSN) {
            LogRecord currentRecord = logManager.fetchLogRecord(currentLSN);
            if (currentRecord.isUndoable()) {
                // How to emit the CLR?
                LogRecord clr = currentRecord.undo(lastRecordLSN);
                lastRecordLSN = logManager.appendToLog(clr);
                clr.redo(this, diskSpaceManager, bufferManager);
            }

            if (!currentRecord.getUndoNextLSN().isPresent()) {
                if (!currentRecord.getPrevLSN().isPresent()) {
                    break;
                } else {
                    currentLSN = currentRecord.getPrevLSN().get();
                }
            } else {
                currentLSN = currentRecord.getUndoNextLSN().get();
            }
        }
        return lastRecordLSN;
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // (proj5): implement
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        long prevLSN = transactionEntry.lastLSN;

        LogRecord record = new UpdatePageLogRecord(transNum, pageNum, prevLSN, pageOffset, before, after);
        long LSN = logManager.appendToLog(record);
        // update transaction table
        transactionEntry.lastLSN = LSN;
        // update dirty page table, note that the dirtyPageTable is pageNum->recLSN
        if (!dirtyPageTable.containsKey(pageNum)) {
            // resLSN is the *first* LSN that dirties the page
            dirtyPageTable.put(pageNum, LSN);
        }
        return LSN;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // (proj5): implement
        rollbackToLSN(transNum, savepointLSN);
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // (proj5): generate end checkpoint record(s) for DPT and transaction table
        // iterate through the DPT and transactionTable to generate end checkpoint records
        Iterator<Map.Entry<Long, Long>> dptIterator = dirtyPageTable.entrySet().iterator();
        Iterator<Map.Entry<Long, TransactionTableEntry>> txnTableIterator = transactionTable.entrySet().iterator();
        while (true) {
            // if add the next DPT entry or transactionTable entry will cause the end checkpoint record too large,
            // append a end checkpoint record with the current chkptDPT and chkptTxnTable.
            // Then clear the two tables and continue copying items until all the entries have been copied.
            if (!EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size() + 1, chkptTxnTable.size()) ||
                !EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size(), chkptTxnTable.size() + 1)) {
                // append a new checkpoint record with current chkptDPT and chkptTxnTable
                LogRecord record = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(record);
                // clear the existing table
                chkptDPT.clear();
                chkptTxnTable.clear();
            }

            if (dptIterator.hasNext()) {
                Map.Entry<Long, Long> entry = dptIterator.next();
                chkptDPT.put(entry.getKey(), entry.getValue());
            } else if (txnTableIterator.hasNext()) {
                Map.Entry<Long, TransactionTableEntry> entry = txnTableIterator.next();
                Transaction.Status status = entry.getValue().transaction.getStatus();
                long lastLSN = entry.getValue().lastLSN;
                chkptTxnTable.put(entry.getKey(), new Pair<Transaction.Status, Long>(status, lastLSN));
            } else {
                break;
            }
        }
        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related, update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records are processed, cleanup and end transactions that are in
     * the COMMITING state, and move all transactions in the RUNNING state to
     * RECOVERY_ABORTING/emit an abort record.
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // (proj5): implement
        // get the iterator to iterate through the records
        Iterator<LogRecord> recordIterator = logManager.scanFrom(LSN);
        while (recordIterator.hasNext()) {
            LogRecord logRecord = recordIterator.next();
            LogType logType = logRecord.getType();

            if (logRecord.getTransNum().isPresent()) {
                // case 1: if the log record is for a transaction operation (getTransNum is present)
                long transNum = logRecord.getTransNum().get();
                if (!transactionTable.containsKey(transNum)) {
                    // The Function interface of Java can be used as follows:
                    // Given Function<T, R> you can call .apply(T t) to get the returned R typed object.
                    // In this case, we can call newTransaction.apply(transNum) to get a new transaction for
                    // recovery purpose.
                    Transaction t = newTransaction.apply(transNum);
                    startTransaction(t);
                    transactionTable.put(transNum, new TransactionTableEntry(t));
                }
                // update the lastLSN of the transaction. a.k.a set the lastLSN of the transaction to the LSN of
                // the record we are on.
                if (transactionTable.get(transNum).lastLSN < logRecord.getLSN()) {
                    transactionTable.get(transNum).lastLSN = logRecord.getLSN();
                }
            }

            if (logRecord.getPageNum().isPresent()) {
                // case 2: if the log record is page-related
                long pageNum = logRecord.getPageNum().get();
                if (logType == LogType.UPDATE_PAGE || logType == LogType.UNDO_UPDATE_PAGE) {
                    // update the dirtyPageTable, since they will dirty pages
                    if (!dirtyPageTable.containsKey(pageNum)) {
                        // should not directly update dirtyPageTable, will fail tests in TestDatabaseRecoveryIntegration
                        dirtyPageTable.put(pageNum, logRecord.getLSN());
                    }
                } else if (logType == LogType.FREE_PAGE || logType == LogType.UNDO_ALLOC_PAGE) {
                    // these two types of change can be seen as flushing the freed page to disk
                    // remove from dirtyPageTable
                    dirtyPageTable.remove(pageNum);
                } else {
                    // do nothing if it is alloc/undofree page
                }
            }

            if (logType == LogType.COMMIT_TRANSACTION || logType == LogType.ABORT_TRANSACTION ||
                    logType == LogType.END_TRANSACTION) {
                // case 3: if the log record is for a change in transaction status:
                TransactionTableEntry entry = transactionTable.get(logRecord.getTransNum().get());

                if (logType == LogType.COMMIT_TRANSACTION) {
                    entry.transaction.setStatus(Transaction.Status.COMMITTING);
                } else if (logType == LogType.ABORT_TRANSACTION) {
                    entry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                } else {
                    long transNum = entry.transaction.getTransNum();
                    entry.transaction.cleanup();
                    entry.transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.remove(transNum);
                    endedTransactions.add(transNum);
                }
            }

            if (logType == LogType.END_CHECKPOINT) {
                Map<Long, Long> chkptDPT = logRecord.getDirtyPageTable();
                Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = logRecord.getTransactionTable();

                for (Map.Entry<Long, Long> chkptDPEntry : chkptDPT.entrySet()) {
                    // recLSN of a page in the chkptDPT is always used
                    dirtyPageTable.put(chkptDPEntry.getKey(), chkptDPEntry.getValue());
                }

                for (Map.Entry<Long, Pair<Transaction.Status, Long>> chkptTxn : chkptTxnTable.entrySet()) {
                    long txnNum = chkptTxn.getKey();
                    Pair<Transaction.Status, Long> pair = chkptTxn.getValue();
                    long chkptLastLSN = pair.getSecond();
                    Transaction.Status chkptStatus = pair.getFirst();

                    if (endedTransactions.contains(chkptTxn.getKey())) {
                        // the transaction in the endedTransactions can be ignored
                        continue;
                    }

                    if (!transactionTable.containsKey(txnNum)) {
                        Transaction t = newTransaction.apply(txnNum);
                        startTransaction(t);
                        transactionTable.put(txnNum, new TransactionTableEntry(t));
                    }
                    // now we can safely get transactionTableEntry using the transaction number
                    TransactionTableEntry entry = transactionTable.get(txnNum);
                    if (entry.lastLSN < chkptLastLSN) {
                        entry.lastLSN = chkptLastLSN;
                    }

                    // update the status of the transaction
                    if (chkptStatus == Transaction.Status.ABORTING &&
                            entry.transaction.getStatus() == Transaction.Status.RUNNING) {
                        // note that the status set here should be RECOVERY_ABORTING
                        entry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                    } else if (chkptStatus == Transaction.Status.COMMITTING &&
                            entry.transaction.getStatus() == Transaction.Status.RUNNING) {
                        entry.transaction.setStatus(chkptStatus);
                    }
                }
            }
        }
        endingTransactions();
    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - about a partition (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // (proj5): implement
        long redoLSN = Integer.MAX_VALUE;
        for (long recLSN : dirtyPageTable.values()) {
            if (recLSN < redoLSN) {
                redoLSN = recLSN;
            }
        }
        // what if the restartRedo is called on an empty log?
        // what is an empty log?
//        if (dirtyPageTable.isEmpty()) {
//            redoLSN = logManager.getFlushedLSN();
//        }
        Iterator<LogRecord> redoIterator = logManager.scanFrom(redoLSN);
        while (redoIterator.hasNext()) {
            LogRecord record = redoIterator.next();
            LogType logType = record.getType();
            if (record.isRedoable()) {
                if (logType.equals(LogType.ALLOC_PART) || logType.equals(LogType.UNDO_ALLOC_PART) ||
                        logType.equals(LogType.FREE_PART) || logType.equals(LogType.UNDO_FREE_PART)) {
                    // always redo if log type is about a partition
                    record.redo(this, diskSpaceManager, bufferManager);
                } else if (logType.equals(LogType.ALLOC_PAGE) || logType.equals(LogType.UNDO_FREE_PAGE)) {
                    record.redo(this, diskSpaceManager, bufferManager);
                } else if (logType.equals(LogType.UPDATE_PAGE) || logType.equals(LogType.UNDO_UPDATE_PAGE) ||
                        logType.equals(LogType.FREE_PAGE) || logType.equals(LogType.UNDO_ALLOC_PAGE)) {
                    Page page = bufferManager.fetchPage(new DummyLockContext(), record.getPageNum().get());
                    try {
                        if (dirtyPageTable.containsKey(record.getPageNum().get())) {
                            long pageLSN = page.getPageLSN();
                            long pageNum = record.getPageNum().get();
                            long recordLSN = record.getLSN();
                            if (recordLSN>= dirtyPageTable.get(pageNum) && pageLSN < recordLSN) {
                                // all the conditions meet, redo this record
                                record.redo(this, diskSpaceManager, bufferManager);
                            }
                        }
                    } finally {
                        page.unpin();
                    }
                }
            }

        }
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and emit the appropriate CLR
     * - replace the entry in the set should be replaced with a new one, using the undoNextLSN
     *   (or prevLSN if not available) of the record; and
     * - if the new LSN is 0, end the transaction and remove it from the queue and transaction table.
     */
    void restartUndo() {
        // (proj5): implement
        PriorityQueue<Long> txnPQ = new PriorityQueue<>(Collections.reverseOrder());
        for (TransactionTableEntry entry : transactionTable.values()) {
            if (entry.transaction.getStatus() == Transaction.Status.RECOVERY_ABORTING) {
                txnPQ.add(entry.lastLSN);
            }
        }

        while (!txnPQ.isEmpty()) {
            long lastLSN = txnPQ.poll();
            LogRecord undoRecord = logManager.fetchLogRecord(lastLSN);

            if (undoRecord.isUndoable()) {
                long transNum = undoRecord.getTransNum().get();
                TransactionTableEntry entry = transactionTable.get(transNum);
                long prevLSN = entry.lastLSN;

                LogRecord clr = undoRecord.undo(prevLSN);
                long clrLSN = logManager.appendToLog(clr);
                entry.lastLSN = clrLSN;
                clr.redo(this, diskSpaceManager, bufferManager);
            }

            long undoNextLSN;
            if (undoRecord.getUndoNextLSN().isPresent()) {
                undoNextLSN = undoRecord.getUndoNextLSN().get();
            } else {
                undoNextLSN = undoRecord.getPrevLSN().get();
            }

            if (undoNextLSN == 0) {
                // https://piazza.com/class/k5ecyhh3xdw1dd?cid=902_f80 says to avoid using end here YOLO
                TransactionTableEntry entry = transactionTable.get(undoRecord.getTransNum().get());
                entry.transaction.cleanup();
                entry.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));
                transactionTable.remove(entry.transaction.getTransNum());
            } else {
                txnPQ.add(undoNextLSN);
            }
        }
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }

    /**
     * This helper method is used to do the work described in Ending Transactions section:
     *
     * After all records are processed, cleanup and end transactions that are in
     * the COMMITING state, and move all transactions in the RUNNING state to
     * RECOVERY_ABORTING/emit an abort record.
     */
    private void endingTransactions() {
        for (TransactionTableEntry entry : transactionTable.values()) {
            if (entry.transaction.getStatus() == Transaction.Status.COMMITTING) {
                // if the entry that are in the COMMITTING state, cleanup and end it.
                entry.transaction.cleanup();
                entry.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN));
                transactionTable.remove(entry.transaction.getTransNum());
            } else if (entry.transaction.getStatus() == Transaction.Status.RUNNING) {
                // if the transaction that are in RUNNING state, move it to RECOVERY_ABORTING
                // and emit an abort record
                entry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                LogRecord recAbort = new AbortTransactionLogRecord(entry.transaction.getTransNum(), entry.lastLSN);
                entry.lastLSN = logManager.appendToLog(recAbort); // emit and abort record.
            }
        }
    }


}
