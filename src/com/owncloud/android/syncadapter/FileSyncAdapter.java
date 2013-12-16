/* ownCloud Android client application
 *   Copyright (C) 2011  Bartek Przybylski
 *   Copyright (C) 2012-2013 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.syncadapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.webdav.DavException;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AuthenticatorActivity;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult;
import com.owncloud.android.operations.SynchronizeFolderOperation;
import com.owncloud.android.operations.UpdateOCVersionOperation;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.ui.activity.ErrorsWhileCopyingHandlerActivity;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.Log_OC;


import android.accounts.Account;
import android.accounts.AccountsException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;

/**
 * Implementation of {@link AbstractThreadedSyncAdapter} responsible for synchronizing 
 * ownCloud files.
 * 
 * Performs a full synchronization of the account recieved in {@link #onPerformSync(Account, Bundle, String, ContentProviderClient, SyncResult)}.
 * 
 * @author Bartek Przybylski
 * @author David A. Velasco
 */
public class FileSyncAdapter extends AbstractOwnCloudSyncAdapter {

    private final static String TAG = FileSyncAdapter.class.getSimpleName();

    /** Maximum number of failed folder synchronizations that are supported before finishing the synchronization operation */
    private static final int MAX_FAILED_RESULTS = 3; 
    
    
    /** Time stamp for the current synchronization process, used to distinguish fresh data */
    private long mCurrentSyncTime;
    
    /** Flag made 'true' when a request to cancel the synchronization is received */
    private boolean mCancellation;
    
    /** When 'true' the process was requested by the user through the user interface; when 'false', it was requested automatically by the system */
    private boolean mIsManualSync;
    
    /** Counter for failed operations in the synchronization process */
    private int mFailedResultsCounter;
    
    /** Result of the last failed operation */
    private RemoteOperationResult mLastFailedResult;
    
    /** Counter of conflicts found between local and remote files */
    private int mConflictsFound;
    
    /** Counter of failed operations in synchronization of kept-in-sync files */
    private int mFailsInFavouritesFound;
    
    /** Map of remote and local paths to files that where locally stored in a location out of the ownCloud folder and couldn't be copied automatically into it */
    private Map<String, String> mForgottenLocalFiles;

    /** {@link SyncResult} instance to return to the system when the synchronization finish */
    private SyncResult mSyncResult;
    
    
    /**
     * Creates a {@link FileSyncAdapter}
     *
     * {@inheritDoc}
     */
    public FileSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    
    /**
     * Creates a {@link FileSyncAdapter}
     *
     * {@inheritDoc}
     */
    public FileSyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
    }

    
    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void onPerformSync(Account account, Bundle extras,
            String authority, ContentProviderClient providerClient,
            SyncResult syncResult) {

        mCancellation = false;
        mIsManualSync = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
        mFailedResultsCounter = 0;
        mLastFailedResult = null;
        mConflictsFound = 0;
        mFailsInFavouritesFound = 0;
        mForgottenLocalFiles = new HashMap<String, String>();
        mSyncResult = syncResult;
        mSyncResult.fullSyncRequested = false;
        mSyncResult.delayUntil = 60*60*24; // avoid too many automatic synchronizations

        this.setAccount(account);
        this.setContentProviderClient(providerClient);
        this.setStorageManager(new FileDataStorageManager(account, providerClient));
        try {
            this.initClientForCurrentAccount();
        } catch (IOException e) {
            /// the account is unknown for the Synchronization Manager, unreachable this context, or can not be authenticated; don't try this again
            mSyncResult.tooManyRetries = true;
            notifyFailedSynchronization();
            return;
        } catch (AccountsException e) {
            /// the account is unknown for the Synchronization Manager, unreachable this context, or can not be authenticated; don't try this again
            mSyncResult.tooManyRetries = true;
            notifyFailedSynchronization();
            return;
        }
        
        Log_OC.d(TAG, "Synchronization of ownCloud account " + account.name + " starting");
        sendStickyBroadcast(true, null, null);  // message to signal the start of the synchronization to the UI
        
        try {
            updateOCVersion();
            mCurrentSyncTime = System.currentTimeMillis();
            if (!mCancellation) {
                synchronizeFolder(getStorageManager().getFileByPath(OCFile.ROOT_PATH));
                
            } else {
                Log_OC.d(TAG, "Leaving synchronization before synchronizing the root folder because cancelation request");
            }
            
            
        } finally {
            // it's important making this although very unexpected errors occur; that's the reason for the finally
            
            if (mFailedResultsCounter > 0 && mIsManualSync) {
                /// don't let the system synchronization manager retries MANUAL synchronizations
                //      (be careful: "MANUAL" currently includes the synchronization requested when a new account is created and when the user changes the current account)
                mSyncResult.tooManyRetries = true;
                
                /// notify the user about the failure of MANUAL synchronization
                notifyFailedSynchronization();
            }
            if (mConflictsFound > 0 || mFailsInFavouritesFound > 0) {
                notifyFailsInFavourites();
            }
            if (mForgottenLocalFiles.size() > 0) {
                notifyForgottenLocalFiles();
            }
            sendStickyBroadcast(false, null, mLastFailedResult);        // message to signal the end to the UI
        }
        
    }
    
    /**
     * Called by system SyncManager when a synchronization is required to be cancelled.
     * 
     * Sets the mCancellation flag to 'true'. THe synchronization will be stopped later, 
     * before a new folder is fetched. Data of the last folder synchronized will be still 
     * locally saved. 
     * 
     * See {@link #onPerformSync(Account, Bundle, String, ContentProviderClient, SyncResult)}
     * and {@link #synchronizeFolder(String, long)}.
     */
    @Override
    public void onSyncCanceled() {
        Log_OC.d(TAG, "Synchronization of " + getAccount().name + " has been requested to cancel");
        mCancellation = true;
        super.onSyncCanceled();
    }
    
    
    /**
     * Updates the locally stored version value of the ownCloud server
     */
    private void updateOCVersion() {
        UpdateOCVersionOperation update = new UpdateOCVersionOperation(getAccount(), getContext());
        RemoteOperationResult result = update.execute(getClient());
        if (!result.isSuccess()) {
            mLastFailedResult = result; 
        }
    }
    
    
    /**
     *  Synchronizes the list of files contained in a folder identified with its remote path.
     *  
     *  Fetches the list and properties of the files contained in the given folder, including their 
     *  properties, and updates the local database with them.
     *  
     *  Enters in the child folders to synchronize their contents also, following a recursive
     *  depth first strategy. 
     * 
     *  @param folder                   Folder to synchronize.
     */
    private void synchronizeFolder(OCFile folder) {
        
        if (mFailedResultsCounter > MAX_FAILED_RESULTS || isFinisher(mLastFailedResult))
            return;
        
        /*
        OCFile folder, 
        long currentSyncTime, 
        boolean updateFolderProperties,
        boolean syncFullAccount,
        DataStorageManager dataStorageManager, 
        Account account, 
        Context context ) {
            
        }
        */
        // folder synchronization
        SynchronizeFolderOperation synchFolderOp = new SynchronizeFolderOperation(  folder, 
                                                                                    mCurrentSyncTime, 
                                                                                    true,
                                                                                    getStorageManager(), 
                                                                                    getAccount(), 
                                                                                    getContext()
                                                                                  );
        RemoteOperationResult result = synchFolderOp.execute(getClient());
        
        
        // synchronized folder -> notice to UI - ALWAYS, although !result.isSuccess
        sendStickyBroadcast(true, folder.getRemotePath(), null);
        
        // check the result of synchronizing the folder
        if (result.isSuccess() || result.getCode() == ResultCode.SYNC_CONFLICT) {
            
            if (result.getCode() == ResultCode.SYNC_CONFLICT) {
                mConflictsFound += synchFolderOp.getConflictsFound();
                mFailsInFavouritesFound += synchFolderOp.getFailsInFavouritesFound();
            }
            if (synchFolderOp.getForgottenLocalFiles().size() > 0) {
                mForgottenLocalFiles.putAll(synchFolderOp.getForgottenLocalFiles());
            }
            if (result.isSuccess()) {
                // synchronize children folders 
                List<OCFile> children = synchFolderOp.getChildren();
                fetchChildren(folder, children, synchFolderOp.getRemoteFolderChanged());    // beware of the 'hidden' recursion here!
            }
            
        } else {
            // in failures, the statistics for the global result are updated
            if (result.getCode() == RemoteOperationResult.ResultCode.UNAUTHORIZED ||
                    ( result.isIdPRedirection() &&
                            getClient().getCredentials() == null      )) {
                            //MainApp.getAuthTokenTypeSamlSessionCookie().equals(getClient().getAuthTokenType()))) {
                mSyncResult.stats.numAuthExceptions++;
                
            } else if (result.getException() instanceof DavException) {
                mSyncResult.stats.numParseExceptions++;
                
            } else if (result.getException() instanceof IOException) { 
                mSyncResult.stats.numIoExceptions++;
            }
            mFailedResultsCounter++;
            mLastFailedResult = result;
        }
            
    }

    /**
     * Checks if a failed result should terminate the synchronization process immediately, according to
     * OUR OWN POLICY
     * 
     * @param   failedResult        Remote operation result to check.
     * @return                      'True' if the result should immediately finish the synchronization
     */
    private boolean isFinisher(RemoteOperationResult failedResult) {
        if  (failedResult != null) {
            RemoteOperationResult.ResultCode code = failedResult.getCode();
            return (code.equals(RemoteOperationResult.ResultCode.SSL_ERROR) ||
                    code.equals(RemoteOperationResult.ResultCode.SSL_RECOVERABLE_PEER_UNVERIFIED) ||
                    code.equals(RemoteOperationResult.ResultCode.BAD_OC_VERSION) ||
                    code.equals(RemoteOperationResult.ResultCode.INSTANCE_NOT_CONFIGURED));
        }
        return false;
    }

    /**
     * Triggers the synchronization of any folder contained in the list of received files.
     * 
     * @param files         Files to recursively synchronize.
     */
    private void fetchChildren(OCFile parent, List<OCFile> files, boolean parentEtagChanged) {
        int i;
        OCFile newFile = null;
        String etag = null;
        boolean syncDown = false;
        for (i=0; i < files.size() && !mCancellation; i++) {
            newFile = files.get(i);
            if (newFile.isFolder()) {
                /*
                etag = newFile.getEtag();
                syncDown = (parentEtagChanged || etag == null || etag.length() == 0);
                if(syncDown) { */
                    synchronizeFolder(newFile);
                    // update the size of the parent folder again after recursive synchronization 
                    //getStorageManager().updateFolderSize(parent.getFileId());  
                    sendStickyBroadcast(true, parent.getRemotePath(), null);        // notify again to refresh size in UI
                //}
            }
        }
       
        if (mCancellation && i <files.size()) Log_OC.d(TAG, "Leaving synchronization before synchronizing " + files.get(i).getRemotePath() + " due to cancelation request");
    }

    
    /**
     * Sends a message to any application component interested in the progress of the synchronization.
     * 
     * @param inProgress        'True' when the synchronization progress is not finished.
     * @param dirRemotePath     Remote path of a folder that was just synchronized (with or without success)
     */
    private void sendStickyBroadcast(boolean inProgress, String dirRemotePath, RemoteOperationResult result) {
        Intent i = new Intent(FileSyncService.getSyncMessage());
        i.putExtra(FileSyncService.IN_PROGRESS, inProgress);
        i.putExtra(FileSyncService.ACCOUNT_NAME, getAccount().name);
        if (dirRemotePath != null) {
            i.putExtra(FileSyncService.SYNC_FOLDER_REMOTE_PATH, dirRemotePath);
        }
        if (result != null) {
            i.putExtra(FileSyncService.SYNC_RESULT, result);
        }
        getContext().sendStickyBroadcast(i);
    }

    
    
    /**
     * Notifies the user about a failed synchronization through the status notification bar 
     */
    private void notifyFailedSynchronization() {
        Notification notification = new Notification(DisplayUtils.getSeasonalIconId(), getContext().getString(R.string.sync_fail_ticker), System.currentTimeMillis());
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        boolean needsToUpdateCredentials = (mLastFailedResult != null && 
                                             (  mLastFailedResult.getCode() == ResultCode.UNAUTHORIZED ||
                                                ( mLastFailedResult.isIdPRedirection() && 
                                                  getClient().getCredentials() == null      )
                                                 //MainApp.getAuthTokenTypeSamlSessionCookie().equals(getClient().getAuthTokenType()))
                                             )
                                           );
        // TODO put something smart in the contentIntent below for all the possible errors
        notification.contentIntent = PendingIntent.getActivity(getContext().getApplicationContext(), (int)System.currentTimeMillis(), new Intent(), 0);
        if (needsToUpdateCredentials) {
            // let the user update credentials with one click
            Intent updateAccountCredentials = new Intent(getContext(), AuthenticatorActivity.class);
            updateAccountCredentials.putExtra(AuthenticatorActivity.EXTRA_ACCOUNT, getAccount());
            updateAccountCredentials.putExtra(AuthenticatorActivity.EXTRA_ENFORCED_UPDATE, true);
            updateAccountCredentials.putExtra(AuthenticatorActivity.EXTRA_ACTION, AuthenticatorActivity.ACTION_UPDATE_TOKEN);
            updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            updateAccountCredentials.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            updateAccountCredentials.addFlags(Intent.FLAG_FROM_BACKGROUND);
            notification.contentIntent = PendingIntent.getActivity(getContext(), (int)System.currentTimeMillis(), updateAccountCredentials, PendingIntent.FLAG_ONE_SHOT);
            notification.setLatestEventInfo(getContext().getApplicationContext(), 
                    getContext().getString(R.string.sync_fail_ticker), 
                    String.format(getContext().getString(R.string.sync_fail_content_unauthorized), getAccount().name), 
                    notification.contentIntent);
        } else {
            notification.setLatestEventInfo(getContext().getApplicationContext(), 
                                            getContext().getString(R.string.sync_fail_ticker), 
                                            String.format(getContext().getString(R.string.sync_fail_content), getAccount().name), 
                                            notification.contentIntent);
        }
        ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).notify(R.string.sync_fail_ticker, notification);
    }


    /**
     * Notifies the user about conflicts and strange fails when trying to synchronize the contents of kept-in-sync files.
     * 
     * By now, we won't consider a failed synchronization.
     */
    private void notifyFailsInFavourites() {
        if (mFailedResultsCounter > 0) {
            Notification notification = new Notification(DisplayUtils.getSeasonalIconId(), getContext().getString(R.string.sync_fail_in_favourites_ticker), System.currentTimeMillis());
            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            // TODO put something smart in the contentIntent below
            notification.contentIntent = PendingIntent.getActivity(getContext().getApplicationContext(), (int)System.currentTimeMillis(), new Intent(), 0);
            notification.setLatestEventInfo(getContext().getApplicationContext(), 
                                            getContext().getString(R.string.sync_fail_in_favourites_ticker), 
                                            String.format(getContext().getString(R.string.sync_fail_in_favourites_content), mFailedResultsCounter + mConflictsFound, mConflictsFound), 
                                            notification.contentIntent);
            ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).notify(R.string.sync_fail_in_favourites_ticker, notification);
            
        } else {
            Notification notification = new Notification(DisplayUtils.getSeasonalIconId(), getContext().getString(R.string.sync_conflicts_in_favourites_ticker), System.currentTimeMillis());
            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            // TODO put something smart in the contentIntent below
            notification.contentIntent = PendingIntent.getActivity(getContext().getApplicationContext(), (int)System.currentTimeMillis(), new Intent(), 0);
            notification.setLatestEventInfo(getContext().getApplicationContext(), 
                                            getContext().getString(R.string.sync_conflicts_in_favourites_ticker), 
                                            String.format(getContext().getString(R.string.sync_conflicts_in_favourites_content), mConflictsFound), 
                                            notification.contentIntent);
            ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).notify(R.string.sync_conflicts_in_favourites_ticker, notification);
        } 
    }
    
    /**
     * Notifies the user about local copies of files out of the ownCloud local directory that were 'forgotten' because 
     * copying them inside the ownCloud local directory was not possible.
     * 
     * We don't want links to files out of the ownCloud local directory (foreign files) anymore. It's easy to have 
     * synchronization problems if a local file is linked to more than one remote file.
     * 
     * We won't consider a synchronization as failed when foreign files can not be copied to the ownCloud local directory.
     */
    private void notifyForgottenLocalFiles() {
        Notification notification = new Notification(DisplayUtils.getSeasonalIconId(), getContext().getString(R.string.sync_foreign_files_forgotten_ticker), System.currentTimeMillis());
        notification.flags |= Notification.FLAG_AUTO_CANCEL;

        /// includes a pending intent in the notification showing a more detailed explanation
        Intent explanationIntent = new Intent(getContext(), ErrorsWhileCopyingHandlerActivity.class);
        explanationIntent.putExtra(ErrorsWhileCopyingHandlerActivity.EXTRA_ACCOUNT, getAccount());
        ArrayList<String> remotePaths = new ArrayList<String>();
        ArrayList<String> localPaths = new ArrayList<String>();
        remotePaths.addAll(mForgottenLocalFiles.keySet());
        localPaths.addAll(mForgottenLocalFiles.values());
        explanationIntent.putExtra(ErrorsWhileCopyingHandlerActivity.EXTRA_LOCAL_PATHS, localPaths);
        explanationIntent.putExtra(ErrorsWhileCopyingHandlerActivity.EXTRA_REMOTE_PATHS, remotePaths);  
        explanationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        notification.contentIntent = PendingIntent.getActivity(getContext().getApplicationContext(), (int)System.currentTimeMillis(), explanationIntent, 0);
        notification.setLatestEventInfo(getContext().getApplicationContext(), 
                                        getContext().getString(R.string.sync_foreign_files_forgotten_ticker), 
                                        String.format(getContext().getString(R.string.sync_foreign_files_forgotten_content), mForgottenLocalFiles.size(), getContext().getString(R.string.app_name)), 
                                        notification.contentIntent);
        ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).notify(R.string.sync_foreign_files_forgotten_ticker, notification);
        
    }
    
    
}
