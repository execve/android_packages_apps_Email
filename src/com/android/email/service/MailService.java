/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.service;

import com.android.email.AccountBackupRestore;
import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.NotificationController;
import com.android.email.Preferences;
import com.android.email.SecurityPolicy;
import com.android.email.SingleRunningTask;
import com.android.email.Utility;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.emailcommon.utility.AccountReconciler;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SyncStatusObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Background service for refreshing non-push email accounts.
 *
 * TODO: Convert to IntentService to move *all* work off the UI thread, serialize work, and avoid
 * possible problems with out-of-order startId processing.
 */
public class MailService extends Service {
    private static final String LOG_TAG = "Email-MailService";

    private static final String ACTION_CHECK_MAIL =
        "com.android.email.intent.action.MAIL_SERVICE_WAKEUP";
    private static final String ACTION_RESCHEDULE =
        "com.android.email.intent.action.MAIL_SERVICE_RESCHEDULE";
    private static final String ACTION_CANCEL =
        "com.android.email.intent.action.MAIL_SERVICE_CANCEL";
    private static final String ACTION_NOTIFY_MAIL =
        "com.android.email.intent.action.MAIL_SERVICE_NOTIFY";
    private static final String ACTION_SEND_PENDING_MAIL =
        "com.android.email.intent.action.MAIL_SERVICE_SEND_PENDING";
    private static final String ACTION_DELETE_EXCHANGE_ACCOUNTS =
        "com.android.email.intent.action.MAIL_SERVICE_DELETE_EXCHANGE_ACCOUNTS";

    private static final String EXTRA_ACCOUNT = "com.android.email.intent.extra.ACCOUNT";
    private static final String EXTRA_ACCOUNT_INFO = "com.android.email.intent.extra.ACCOUNT_INFO";
    private static final String EXTRA_DEBUG_WATCHDOG = "com.android.email.intent.extra.WATCHDOG";

    private static final int WATCHDOG_DELAY = 10 * 60 * 1000;   // 10 minutes

    // Sentinel value asking to update mSyncReports if it's currently empty
    /*package*/ static final int SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY = -1;
    // Sentinel value asking that mSyncReports be rebuilt
    /*package*/ static final int SYNC_REPORTS_RESET = -2;

    private static final String[] NEW_MESSAGE_COUNT_PROJECTION =
        new String[] {AccountColumns.NEW_MESSAGE_COUNT};

    private static MailService sMailService;

    /*package*/ Controller mController;
    private final Controller.Result mControllerCallback = new ControllerResults();
    private ContentResolver mContentResolver;
    private Context mContext;
    private Handler mHandler = new Handler();

    private int mStartId;

    /**
     * Access must be synchronized, because there are accesses from the Controller callback
     */
    /*package*/ static HashMap<Long,AccountSyncReport> mSyncReports =
        new HashMap<Long,AccountSyncReport>();

    public static void actionReschedule(Context context) {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_RESCHEDULE);
        context.startService(i);
    }

    public static void actionCancel(Context context)  {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_CANCEL);
        context.startService(i);
    }

    public static void actionDeleteExchangeAccounts(Context context)  {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_DELETE_EXCHANGE_ACCOUNTS);
        context.startService(i);
    }

    /**
     * Entry point for AttachmentDownloadService to ask that pending mail be sent
     * @param context the caller's context
     * @param accountId the account whose pending mail should be sent
     */
    public static void actionSendPendingMail(Context context, long accountId)  {
        Intent i = new Intent();
        i.setClass(context, MailService.class);
        i.setAction(MailService.ACTION_SEND_PENDING_MAIL);
        i.putExtra(MailService.EXTRA_ACCOUNT, accountId);
        context.startService(i);
    }

    /**
     * Reset new message counts for one or all accounts.  This clears both our local copy and
     * the values (if any) stored in the account records.
     *
     * @param accountId account to clear, or -1 for all accounts
     */
    public static void resetNewMessageCount(final Context context, final long accountId) {
        synchronized (mSyncReports) {
            for (AccountSyncReport report : mSyncReports.values()) {
                if (accountId == -1 || accountId == report.accountId) {
                    report.unseenMessageCount = 0;
                    report.lastUnseenMessageCount = 0;
                }
            }
        }
        // Clear notification
        NotificationController.getInstance(context).cancelNewMessageNotification(accountId);

        // now do the database - all accounts, or just one of them
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                Uri uri = Account.RESET_NEW_MESSAGE_COUNT_URI;
                if (accountId != -1) {
                    uri = ContentUris.withAppendedId(uri, accountId);
                }
                context.getContentResolver().update(uri, null, null, null);
            }
        });
    }

    /**
     * Entry point for asynchronous message services (e.g. push mode) to post notifications of new
     * messages.  This assumes that the push provider has already synced the messages into the
     * appropriate database - this simply triggers the notification mechanism.
     *
     * @param context a context
     * @param accountId the id of the account that is reporting new messages
     */
    public static void actionNotifyNewMessages(Context context, long accountId) {
        Intent i = new Intent(ACTION_NOTIFY_MAIL);
        i.setClass(context, MailService.class);
        i.putExtra(EXTRA_ACCOUNT, accountId);
        context.startService(i);
    }

    /*package*/ static MailService getMailServiceForTest() {
        return sMailService;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        super.onStartCommand(intent, flags, startId);

        // Save the service away (for unit tests)
        sMailService = this;

        // Restore accounts, if it has not happened already
        AccountBackupRestore.restoreAccountsIfNeeded(this);

        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                reconcilePopImapAccountsSync(MailService.this);
            }
        });

        // TODO this needs to be passed through the controller and back to us
        mStartId = startId;
        String action = intent.getAction();
        final long accountId = intent.getLongExtra(EXTRA_ACCOUNT, -1);

        mController = Controller.getInstance(this);
        mController.addResultCallback(mControllerCallback);
        mContentResolver = getContentResolver();
        mContext = this;

        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        if (ACTION_CHECK_MAIL.equals(action)) {
            // DB access required to satisfy this intent, so offload from UI thread
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    // If we have the data, restore the last-sync-times for each account
                    // These are cached in the wakeup intent in case the process was killed.
                    restoreSyncReports(intent);

                    // Sync a specific account if given
                    if (Email.DEBUG) {
                        Log.d(LOG_TAG, "action: check mail for id=" + accountId);
                    }
                    if (accountId >= 0) {
                        setWatchdog(accountId, alarmManager);
                    }

                    // Start sync if account is given && bg data enabled && account has sync enabled
                    boolean syncStarted = false;
                    if (accountId != -1 && isBackgroundDataEnabled()) {
                        synchronized(mSyncReports) {
                            for (AccountSyncReport report: mSyncReports.values()) {
                                if (report.accountId == accountId) {
                                    if (report.syncEnabled) {
                                        syncStarted = syncOneAccount(mController, accountId,
                                                startId);
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    // Reschedule if we didn't start sync.
                    if (!syncStarted) {
                        // Prevent runaway on the current account by pretending it updated
                        if (accountId != -1) {
                            updateAccountReport(accountId, 0);
                        }
                        // Find next account to sync, and reschedule
                        reschedule(alarmManager);
                        // Stop the service, unless actually syncing (which will stop the service)
                        stopSelf(startId);
                    }
                }
            });
        }
        else if (ACTION_CANCEL.equals(action)) {
            if (Email.DEBUG) {
                Log.d(LOG_TAG, "action: cancel");
            }
            cancel();
            stopSelf(startId);
        }
        else if (ACTION_DELETE_EXCHANGE_ACCOUNTS.equals(action)) {
            if (Email.DEBUG) {
                Log.d(LOG_TAG, "action: delete exchange accounts");
            }
            Utility.runAsync(new Runnable() {
                public void run() {
                    Cursor c = mContentResolver.query(Account.CONTENT_URI, Account.ID_PROJECTION,
                            null, null, null);
                    try {
                        while (c.moveToNext()) {
                            long accountId = c.getLong(Account.ID_PROJECTION_COLUMN);
                            if ("eas".equals(Account.getProtocol(mContext, accountId))) {
                                // Always log this
                                Log.d(LOG_TAG, "Deleting EAS account: " + accountId);
                                mController.deleteAccountSync(accountId, mContext);
                            }
                       }
                    } finally {
                        c.close();
                    }
                }
            });
            stopSelf(startId);
        }
        else if (ACTION_SEND_PENDING_MAIL.equals(action)) {
            if (Email.DEBUG) {
                Log.d(LOG_TAG, "action: send pending mail");
            }
            Utility.runAsync(new Runnable() {
                public void run() {
                    mController.sendPendingMessages(accountId);
                }
            });
            stopSelf(startId);
        }
        else if (ACTION_RESCHEDULE.equals(action)) {
            if (Email.DEBUG) {
                Log.d(LOG_TAG, "action: reschedule");
            }
            final NotificationController nc = NotificationController.getInstance(this);
            // DB access required to satisfy this intent, so offload from UI thread
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    // Clear all notifications, in case account list has changed.
                    //
                    // TODO Clear notifications for non-existing accounts.  Now that we have
                    // separate notifications for each account, NotificationController should be
                    // able to do that.
                    nc.cancelNewMessageNotification(-1);

                    // When called externally, we refresh the sync reports table to pick up
                    // any changes in the account list or account settings
                    refreshSyncReports();
                    // Finally, scan for the next needing update, and set an alarm for it
                    reschedule(alarmManager);
                    stopSelf(startId);
                }
            });
        } else if (ACTION_NOTIFY_MAIL.equals(action)) {
            // DB access required to satisfy this intent, so offload from UI thread
            Utility.runAsync(new Runnable() {
                @Override
                public void run() {
                    // Get the current new message count
                    Cursor c = mContentResolver.query(
                            ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                            NEW_MESSAGE_COUNT_PROJECTION, null, null, null);
                    int newMessageCount = 0;
                    try {
                        if (c.moveToFirst()) {
                            newMessageCount = c.getInt(0);
                            updateAccountReport(accountId, newMessageCount);
                            notifyNewMessages(accountId);
                        }
                    } finally {
                        c.close();
                    }
                    if (Email.DEBUG) {
                        Log.d(LOG_TAG, "notify accountId=" + Long.toString(accountId)
                                + " count=" + newMessageCount);
                    }
                    stopSelf(startId);
                }
            });
        }

        // Returning START_NOT_STICKY means that if a mail check is killed (e.g. due to memory
        // pressure, there will be no explicit restart.  This is OK;  Note that we set a watchdog
        // alarm before each mailbox check.  If the mailbox check never completes, the watchdog
        // will fire and get things running again.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    private void cancel() {
        AlarmManager alarmMgr = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = createAlarmIntent(-1, null, false);
        alarmMgr.cancel(pi);
    }

    /**
     * Refresh the sync reports, to pick up any changes in the account list or account settings.
     */
    /*package*/ void refreshSyncReports() {
        synchronized (mSyncReports) {
            // Make shallow copy of sync reports so we can recover the prev sync times
            HashMap<Long,AccountSyncReport> oldSyncReports =
                new HashMap<Long,AccountSyncReport>(mSyncReports);

            // Delete the sync reports to force a refresh from live account db data
            setupSyncReportsLocked(SYNC_REPORTS_RESET, this);

            // Restore prev-sync & next-sync times for any reports in the new list
            for (AccountSyncReport newReport : mSyncReports.values()) {
                AccountSyncReport oldReport = oldSyncReports.get(newReport.accountId);
                if (oldReport != null) {
                    newReport.prevSyncTime = oldReport.prevSyncTime;
                    if (newReport.syncInterval > 0 && newReport.prevSyncTime != 0) {
                        newReport.nextSyncTime =
                            newReport.prevSyncTime + (newReport.syncInterval * 1000 * 60);
                    }
                }
            }
        }
    }

    /**
     * Create and send an alarm with the entire list.  This also sends a list of known last-sync
     * times with the alarm, so if we are killed between alarms, we don't lose this info.
     *
     * @param alarmMgr passed in so we can mock for testing.
     */
    /* package */ void reschedule(AlarmManager alarmMgr) {
        // restore the reports if lost
        setupSyncReports(SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY);
        synchronized (mSyncReports) {
            int numAccounts = mSyncReports.size();
            long[] accountInfo = new long[numAccounts * 2];     // pairs of { accountId, lastSync }
            int accountInfoIndex = 0;

            long nextCheckTime = Long.MAX_VALUE;
            AccountSyncReport nextAccount = null;
            long timeNow = SystemClock.elapsedRealtime();

            for (AccountSyncReport report : mSyncReports.values()) {
                if (report.syncInterval <= 0) {                         // no timed checks - skip
                    continue;
                }
                long prevSyncTime = report.prevSyncTime;
                long nextSyncTime = report.nextSyncTime;

                // select next account to sync
                if ((prevSyncTime == 0) || (nextSyncTime < timeNow)) {  // never checked, or overdue
                    nextCheckTime = 0;
                    nextAccount = report;
                } else if (nextSyncTime < nextCheckTime) {              // next to be checked
                    nextCheckTime = nextSyncTime;
                    nextAccount = report;
                }
                // collect last-sync-times for all accounts
                // this is using pairs of {long,long} to simplify passing in a bundle
                accountInfo[accountInfoIndex++] = report.accountId;
                accountInfo[accountInfoIndex++] = report.prevSyncTime;
            }

            // Clear out any unused elements in the array
            while (accountInfoIndex < accountInfo.length) {
                accountInfo[accountInfoIndex++] = -1;
            }

            // set/clear alarm as needed
            long idToCheck = (nextAccount == null) ? -1 : nextAccount.accountId;
            PendingIntent pi = createAlarmIntent(idToCheck, accountInfo, false);

            if (nextAccount == null) {
                alarmMgr.cancel(pi);
                if (Email.DEBUG) {
                    Log.d(LOG_TAG, "reschedule: alarm cancel - no account to check");
                }
            } else {
                alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextCheckTime, pi);
                if (Email.DEBUG) {
                    Log.d(LOG_TAG, "reschedule: alarm set at " + nextCheckTime
                            + " for " + nextAccount);
                }
            }
        }
    }

    /**
     * Create a watchdog alarm and set it.  This is used in case a mail check fails (e.g. we are
     * killed by the system due to memory pressure.)  Normally, a mail check will complete and
     * the watchdog will be replaced by the call to reschedule().
    * @param accountId the account we were trying to check
     * @param alarmMgr system alarm manager
     */
    private void setWatchdog(long accountId, AlarmManager alarmMgr) {
        PendingIntent pi = createAlarmIntent(accountId, null, true);
        long timeNow = SystemClock.elapsedRealtime();
        long nextCheckTime = timeNow + WATCHDOG_DELAY;
        alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextCheckTime, pi);
    }

    /**
     * Return a pending intent for use by this alarm.  Most of the fields must be the same
     * (in order for the intent to be recognized by the alarm manager) but the extras can
     * be different, and are passed in here as parameters.
     */
    /* package */ PendingIntent createAlarmIntent(long checkId, long[] accountInfo,
            boolean isWatchdog) {
        Intent i = new Intent();
        i.setClass(this, MailService.class);
        i.setAction(ACTION_CHECK_MAIL);
        i.putExtra(EXTRA_ACCOUNT, checkId);
        i.putExtra(EXTRA_ACCOUNT_INFO, accountInfo);
        if (isWatchdog) {
            i.putExtra(EXTRA_DEBUG_WATCHDOG, true);
        }
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return pi;
    }

    /**
     * Start a controller sync for a specific account
     *
     * @param controller The controller to do the sync work
     * @param checkAccountId the account Id to try and check
     * @param startId the id of this service launch
     * @return true if mail checking has started, false if it could not (e.g. bad account id)
     */
    private boolean syncOneAccount(Controller controller, long checkAccountId, int startId) {
        long inboxId = Mailbox.findMailboxOfType(this, checkAccountId, Mailbox.TYPE_INBOX);
        if (inboxId == Mailbox.NO_MAILBOX) {
            return false;
        } else {
            controller.serviceCheckMail(checkAccountId, inboxId, startId);
            return true;
        }
    }

    /**
     * Note:  Times are relative to SystemClock.elapsedRealtime()
     *
     * TODO:  Look more closely at syncEnabled and see if we can simply coalesce it into
     * syncInterval (e.g. if !syncEnabled, set syncInterval to -1).
     */
    /*package*/ static class AccountSyncReport {
        long accountId;
        long prevSyncTime;      // 0 == unknown
        long nextSyncTime;      // 0 == ASAP  -1 == don't sync

        /** # of "unseen" messages to show in notification */
        int unseenMessageCount;

        /**
         * # of unseen, the value shown on the last notification. Used to
         * calculate "the number of messages that have just been fetched".
         *
         * TODO It's a sort of cheating.  Should we use the "real" number?  The only difference
         * is the first notification after reboot / process restart.
         */
        int lastUnseenMessageCount;

        int syncInterval;
        boolean notify;

        boolean syncEnabled;    // whether auto sync is enabled for this account

        /** # of messages that have just been fetched */
        int getJustFetchedMessageCount() {
            return unseenMessageCount - lastUnseenMessageCount;
        }

        @Override
        public String toString() {
            return "id=" + accountId
                    + " prevSync=" + prevSyncTime + " nextSync=" + nextSyncTime + " numUnseen="
                    + unseenMessageCount;
        }
    }

    /**
     * scan accounts to create a list of { acct, prev sync, next sync, #new }
     * use this to create a fresh copy.  assumes all accounts need sync
     *
     * @param accountId -1 will rebuild the list if empty.  other values will force loading
     *   of a single account (e.g if it was created after the original list population)
     */
    /* package */ void setupSyncReports(long accountId) {
        synchronized (mSyncReports) {
            setupSyncReportsLocked(accountId, mContext);
        }
    }

    /**
     * Handle the work of setupSyncReports.  Must be synchronized on mSyncReports.
     */
    /*package*/ void setupSyncReportsLocked(long accountId, Context context) {
        ContentResolver resolver = context.getContentResolver();
        if (accountId == SYNC_REPORTS_RESET) {
            // For test purposes, force refresh of mSyncReports
            mSyncReports.clear();
            accountId = SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY;
        } else if (accountId == SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY) {
            // -1 == reload the list if empty, otherwise exit immediately
            if (mSyncReports.size() > 0) {
                return;
            }
        } else {
            // load a single account if it doesn't already have a sync record
            if (mSyncReports.containsKey(accountId)) {
                return;
            }
        }

        // setup to add a single account or all accounts
        Uri uri;
        if (accountId == SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY) {
            uri = Account.CONTENT_URI;
        } else {
            uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
        }

        final boolean oneMinuteRefresh
                = Preferences.getPreferences(this).getForceOneMinuteRefresh();
        if (oneMinuteRefresh) {
            Log.w(LOG_TAG, "One-minute refresh enabled.");
        }

        // We use a full projection here because we'll restore each account object from it
        Cursor c = resolver.query(uri, Account.CONTENT_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                Account account = Account.getContent(c, Account.class);
                // The following sanity checks are primarily for the sake of ignoring non-user
                // accounts that may have been left behind e.g. by failed unit tests.
                // Properly-formed accounts will always pass these simple checks.
                if (TextUtils.isEmpty(account.mEmailAddress)
                        || account.mHostAuthKeyRecv <= 0
                        || account.mHostAuthKeySend <= 0) {
                    continue;
                }

                // The account is OK, so proceed
                AccountSyncReport report = new AccountSyncReport();
                int syncInterval = account.mSyncInterval;

                // If we're not using MessagingController (EAS at this point), don't schedule syncs
                if (!mController.isMessagingController(account.mId)) {
                    syncInterval = Account.CHECK_INTERVAL_NEVER;
                } else if (oneMinuteRefresh && syncInterval >= 0) {
                    syncInterval = 1;
                }

                report.accountId = account.mId;
                report.prevSyncTime = 0;
                report.nextSyncTime = (syncInterval > 0) ? 0 : -1;  // 0 == ASAP -1 == no sync
                report.unseenMessageCount = 0;
                report.lastUnseenMessageCount = 0;

                report.syncInterval = syncInterval;
                report.notify = (account.mFlags & Account.FLAGS_NOTIFY_NEW_MAIL) != 0;

                // See if the account is enabled for sync in AccountManager
                android.accounts.Account accountManagerAccount =
                    new android.accounts.Account(account.mEmailAddress,
                            Email.POP_IMAP_ACCOUNT_MANAGER_TYPE);
                report.syncEnabled = ContentResolver.getSyncAutomatically(accountManagerAccount,
                        EmailProvider.EMAIL_AUTHORITY);

                // TODO lookup # new in inbox
                mSyncReports.put(report.accountId, report);
            }
        } finally {
            c.close();
        }
    }

    /**
     * Update list with a single account's sync times and unread count
     *
     * @param accountId the account being updated
     * @param newCount the number of new messages, or -1 if not being reported (don't update)
     * @return the report for the updated account, or null if it doesn't exist (e.g. deleted)
     */
    /* package */ AccountSyncReport updateAccountReport(long accountId, int newCount) {
        // restore the reports if lost
        setupSyncReports(accountId);
        synchronized (mSyncReports) {
            AccountSyncReport report = mSyncReports.get(accountId);
            if (report == null) {
                // discard result - there is no longer an account with this id
                Log.d(LOG_TAG, "No account to update for id=" + Long.toString(accountId));
                return null;
            }

            // report found - update it (note - editing the report while in-place in the hashmap)
            report.prevSyncTime = SystemClock.elapsedRealtime();
            if (report.syncInterval > 0) {
                report.nextSyncTime = report.prevSyncTime + (report.syncInterval * 1000 * 60);
            }
            if (newCount != -1) {
                report.unseenMessageCount = newCount;
            }
            if (Email.DEBUG) {
                Log.d(LOG_TAG, "update account " + report.toString());
            }
            return report;
        }
    }

    /**
     * when we receive an alarm, update the account sync reports list if necessary
     * this will be the case when if we have restarted the process and lost the data
     * in the global.
     *
     * @param restoreIntent the intent with the list
     */
    /* package */ void restoreSyncReports(Intent restoreIntent) {
        // restore the reports if lost
        setupSyncReports(SYNC_REPORTS_ALL_ACCOUNTS_IF_EMPTY);
        synchronized (mSyncReports) {
            long[] accountInfo = restoreIntent.getLongArrayExtra(EXTRA_ACCOUNT_INFO);
            if (accountInfo == null) {
                Log.d(LOG_TAG, "no data in intent to restore");
                return;
            }
            int accountInfoIndex = 0;
            int accountInfoLimit = accountInfo.length;
            while (accountInfoIndex < accountInfoLimit) {
                long accountId = accountInfo[accountInfoIndex++];
                long prevSync = accountInfo[accountInfoIndex++];
                AccountSyncReport report = mSyncReports.get(accountId);
                if (report != null) {
                    if (report.prevSyncTime == 0) {
                        report.prevSyncTime = prevSync;
                        if (report.syncInterval > 0 && report.prevSyncTime != 0) {
                            report.nextSyncTime =
                                report.prevSyncTime + (report.syncInterval * 1000 * 60);
                        }
                    }
                }
            }
        }
    }

    class ControllerResults extends Controller.Result {
        @Override
        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            // First, look for authentication failures and notify
           //checkAuthenticationStatus(result, accountId);
           if (result != null || progress == 100) {
                // We only track the inbox here in the service - ignore other mailboxes
                long inboxId = Mailbox.findMailboxOfType(MailService.this,
                        accountId, Mailbox.TYPE_INBOX);
                if (mailboxId == inboxId) {
                    if (progress == 100) {
                        updateAccountReport(accountId, numNewMessages);
                        if (numNewMessages > 0) {
                            notifyNewMessages(accountId);
                        }
                    } else {
                        updateAccountReport(accountId, -1);
                    }
                }
            }
        }

        @Override
        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
            if (result != null || progress == 100) {
                if (result != null) {
                    // the checkmail ended in an error.  force an update of the refresh
                    // time, so we don't just spin on this account
                    updateAccountReport(accountId, -1);
                }
                AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                reschedule(alarmManager);
                int serviceId = MailService.this.mStartId;
                if (tag != 0) {
                    serviceId = (int) tag;
                }
                stopSelf(serviceId);
            }
        }
    }

    /**
     * Show "new message" notification for an account.  (Notification is shown per account.)
     */
    private void notifyNewMessages(final long accountId) {
        final int unseenMessageCount;
        final int justFetchedCount;
        synchronized (mSyncReports) {
            AccountSyncReport report = mSyncReports.get(accountId);
            if (report == null || report.unseenMessageCount == 0 || !report.notify) {
                return;
            }
            unseenMessageCount = report.unseenMessageCount;
            justFetchedCount = report.getJustFetchedMessageCount();
            report.lastUnseenMessageCount = report.unseenMessageCount;
        }

        NotificationController.getInstance(this).showNewMessageNotification(accountId,
                unseenMessageCount, justFetchedCount);
    }

    /**
     * @see ConnectivityManager#getBackgroundDataSetting()
     */
    private boolean isBackgroundDataEnabled() {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getBackgroundDataSetting();
    }

    public class EmailSyncStatusObserver implements SyncStatusObserver {
        public void onStatusChanged(int which) {
            // We ignore the argument (we can only get called in one case - when settings change)
        }
    }

    public static ArrayList<Account> getPopImapAccountList(Context context) {
        ArrayList<Account> providerAccounts = new ArrayList<Account>();
        Cursor c = context.getContentResolver().query(Account.CONTENT_URI, Account.ID_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                String protocol = Account.getProtocol(context, accountId);
                if ((protocol != null) && ("pop3".equals(protocol) || "imap".equals(protocol))) {
                    Account account = Account.restoreAccountWithId(context, accountId);
                    if (account != null) {
                        providerAccounts.add(account);
                    }
                }
            }
        } finally {
            c.close();
        }
        return providerAccounts;
    }

    private static final SingleRunningTask<Context> sReconcilePopImapAccountsSyncExecutor =
            new SingleRunningTask<Context>("ReconcilePopImapAccountsSync") {
                @Override
                protected void runInternal(Context context) {
                    android.accounts.Account[] accountManagerAccounts = AccountManager.get(context)
                            .getAccountsByType(Email.POP_IMAP_ACCOUNT_MANAGER_TYPE);
                    ArrayList<Account> providerAccounts = getPopImapAccountList(context);
                    MailService.reconcileAccountsWithAccountManager(context, providerAccounts,
                            accountManagerAccounts, false, context.getContentResolver());

                }
    };

    /**
     * Reconcile POP/IMAP accounts.
     */
    public static void reconcilePopImapAccountsSync(Context context) {
        sReconcilePopImapAccountsSyncExecutor.run(context);
    }

    /**
     * Handles a variety of cleanup actions that must be performed when an account has been deleted.
     * This includes triggering an account backup, ensuring that security policies are properly
     * reset, if necessary, notifying the UI of the change, and resetting scheduled syncs and
     * notifications.
     * @param context the caller's context
     */
    public static void accountDeleted(Context context) {
        AccountBackupRestore.backupAccounts(context);
        SecurityPolicy.getInstance(context).reducePolicies();
        Email.setNotifyUiAccountsChanged(true);
        MailService.actionReschedule(context);
    }

    /**
     * See Utility.reconcileAccounts for details
     * @param context The context in which to operate
     * @param emailProviderAccounts the exchange provider accounts to work from
     * @param accountManagerAccounts The account manager accounts to work from
     * @param blockExternalChanges FOR TESTING ONLY - block backups, security changes, etc.
     * @param resolver the content resolver for making provider updates (injected for testability)
     */
    /* package */ public static void reconcileAccountsWithAccountManager(Context context,
            List<Account> emailProviderAccounts, android.accounts.Account[] accountManagerAccounts,
            boolean blockExternalChanges, ContentResolver resolver) {
        boolean accountsDeleted = AccountReconciler.reconcileAccounts(context,
                emailProviderAccounts, accountManagerAccounts, resolver);
        // If we changed the list of accounts, refresh the backup & security settings
        if (!blockExternalChanges && accountsDeleted) {
            accountDeleted(context);
        }
    }

    public static void setupAccountManagerAccount(Context context, EmailContent.Account account,
            boolean email, boolean calendar, boolean contacts,
            AccountManagerCallback<Bundle> callback) {
        Bundle options = new Bundle();
        HostAuth hostAuthRecv = HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        // Set up username/password
        options.putString(EasAuthenticatorService.OPTIONS_USERNAME, account.mEmailAddress);
        options.putString(EasAuthenticatorService.OPTIONS_PASSWORD, hostAuthRecv.mPassword);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CONTACTS_SYNC_ENABLED, contacts);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CALENDAR_SYNC_ENABLED, calendar);
        options.putBoolean(EasAuthenticatorService.OPTIONS_EMAIL_SYNC_ENABLED, email);
        String accountType = hostAuthRecv.mProtocol.equals("eas") ?
                Email.EXCHANGE_ACCOUNT_MANAGER_TYPE :
                Email.POP_IMAP_ACCOUNT_MANAGER_TYPE;
        AccountManager.get(context).addAccount(accountType, null, null, options, null, callback,
                null);
    }
}
