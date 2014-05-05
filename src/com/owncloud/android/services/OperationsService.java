/* ownCloud Android client application
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

package com.owncloud.android.services;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.lib.common.OwnCloudClientFactory;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.OnRemoteOperationListener;
import com.owncloud.android.lib.common.operations.RemoteOperation;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.resources.shares.ShareType;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.operations.CreateShareOperation;
import com.owncloud.android.operations.UnshareLinkOperation;
import com.owncloud.android.utils.Log_OC;

import android.accounts.Account;
import android.accounts.AccountsException;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
//import android.support.v4.content.LocalBroadcastManager;
import android.util.Pair;

public class OperationsService extends Service {
    
    private static final String TAG = OperationsService.class.getSimpleName();
    
    public static final String EXTRA_ACCOUNT = "ACCOUNT";
    public static final String EXTRA_SERVER_URL = "SERVER_URL";
    public static final String EXTRA_REMOTE_PATH = "REMOTE_PATH";
    public static final String EXTRA_SEND_INTENT = "SEND_INTENT";
    public static final String EXTRA_RESULT = "RESULT";
    
    public static final String ACTION_CREATE_SHARE = "CREATE_SHARE";
    public static final String ACTION_UNSHARE = "UNSHARE";
    
    public static final String ACTION_OPERATION_ADDED = OperationsService.class.getName() + ".OPERATION_ADDED";
    public static final String ACTION_OPERATION_FINISHED = OperationsService.class.getName() + ".OPERATION_FINISHED";

    private ConcurrentLinkedQueue<Pair<Target, RemoteOperation>> mPendingOperations = new ConcurrentLinkedQueue<Pair<Target, RemoteOperation>>();
    
    private static class Target {
        public Uri mServerUrl = null;
        public Account mAccount = null;
        public Target(Account account, Uri serverUrl) {
            mAccount = account;
            mServerUrl = serverUrl;
        }
    }

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private OperationsServiceBinder mBinder;
    private OwnCloudClient mOwnCloudClient = null;
    private Target mLastTarget = null;
    private FileDataStorageManager mStorageManager;
    private RemoteOperation mCurrentOperation = null;
    
    
    /**
     * Service initialization
     */
    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("Operations service thread", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper, this);
        mBinder = new OperationsServiceBinder();
    }

    /**
     * Entry point to add a new operation to the queue of operations.
     * 
     * New operations are added calling to startService(), resulting in a call to this method. 
     * This ensures the service will keep on working although the caller activity goes away.
     * 
     * IMPORTANT: the only operations performed here right now is {@link GetSharedFilesOperation}. The class
     * is taking advantage of it due to time constraints.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!intent.hasExtra(EXTRA_ACCOUNT) && !intent.hasExtra(EXTRA_SERVER_URL)) {
            Log_OC.e(TAG, "Not enough information provided in intent");
            return START_NOT_STICKY;
        }
        try {
            Account account = intent.getParcelableExtra(EXTRA_ACCOUNT);
            String serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
            
            Target target = new Target(account, (serverUrl == null) ? null : Uri.parse(serverUrl));
            RemoteOperation operation = null;
            
            String action = intent.getAction();
            if (action.equals(ACTION_CREATE_SHARE)) {  // Create Share
                String remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH);
                Intent sendIntent = intent.getParcelableExtra(EXTRA_SEND_INTENT);
                if (remotePath.length() > 0) {
                    operation = new CreateShareOperation(remotePath, ShareType.PUBLIC_LINK, 
                            "", false, "", 1, sendIntent);
                }
            } else if (action.equals(ACTION_UNSHARE)) {  // Unshare file
                String remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH);
                if (remotePath.length() > 0) {
                    operation = new UnshareLinkOperation(remotePath, this.getApplicationContext());
                }
            } else {
                // nothing we are going to handle
                return START_NOT_STICKY;
            }
            
            mPendingOperations.add(new Pair<Target , RemoteOperation>(target, operation));
            //sendBroadcastNewOperation(target, operation);
            
            Message msg = mServiceHandler.obtainMessage();
            msg.arg1 = startId;
            mServiceHandler.sendMessage(msg);
            
        } catch (IllegalArgumentException e) {
            Log_OC.e(TAG, "Bad information provided in intent: " + e.getMessage());
            return START_NOT_STICKY;
        }
        
        return START_NOT_STICKY;
    }

    
    /**
     * Provides a binder object that clients can use to perform actions on the queue of operations, 
     * except the addition of new operations. 
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    
    /**
     * Called when ALL the bound clients were unbound.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        //((OperationsServiceBinder)mBinder).clearListeners();
        return false;   // not accepting rebinding (default behaviour)
    }

    
    /**
     *  Binder to let client components to perform actions on the queue of operations.
     * 
     *  It provides by itself the available operations.
     */
    public class OperationsServiceBinder extends Binder /* implements OnRemoteOperationListener */ {
        
        /** 
         * Map of listeners that will be reported about the end of operations from a {@link OperationsServiceBinder} instance 
         */
        private Map<OnRemoteOperationListener, Handler> mBoundListeners = new HashMap<OnRemoteOperationListener, Handler>();
        
        /**
         * Cancels an operation
         *
         * TODO
         */
        public void cancel() {
            // TODO
        }
        
        
        public void clearListeners() {
            
            mBoundListeners.clear();
        }

        
        /**
         * Adds a listener interested in being reported about the end of operations.
         * 
         * @param listener          Object to notify about the end of operations.    
         * @param callbackHandler   {@link Handler} to access the listener without breaking Android threading protection.
         */
        public void addOperationListener (OnRemoteOperationListener listener, Handler callbackHandler) {
            mBoundListeners.put(listener, callbackHandler);
        }
        
        
        /**
         * Removes a listener from the list of objects interested in the being reported about the end of operations.
         * 
         * @param listener      Object to notify about progress of transfer.    
         */
        public void removeOperationListener (OnRemoteOperationListener listener) {
            mBoundListeners.remove(listener);
        }


        /**
         * TODO - IMPORTANT: update implementation when more operations are moved into the service 
         * 
         * @return  'True' when an operation that enforces the user to wait for completion is in process.
         */
        public boolean isPerformingBlockingOperation() {
            return (!mPendingOperations.isEmpty());
        }

    }
    
    
    /** 
     * Operations worker. Performs the pending operations in the order they were requested. 
     * 
     * Created with the Looper of a new thread, started in {@link OperationsService#onCreate()}. 
     */
    private static class ServiceHandler extends Handler {
        // don't make it a final class, and don't remove the static ; lint will warn about a possible memory leak
        OperationsService mService;
        public ServiceHandler(Looper looper, OperationsService service) {
            super(looper);
            if (service == null) {
                throw new IllegalArgumentException("Received invalid NULL in parameter 'service'");
            }
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            mService.nextOperation();
            mService.stopSelf(msg.arg1);
        }
    }
    

    /**
     * Performs the next operation in the queue
     */
    private void nextOperation() {
        
        Pair<Target, RemoteOperation> next = null;
        synchronized(mPendingOperations) {
            next = mPendingOperations.peek();
        }
        
        if (next != null) {
            
            mCurrentOperation = next.second;
            RemoteOperationResult result = null;
            try {
                /// prepare client object to send the request to the ownCloud server
                if (mLastTarget == null || !mLastTarget.equals(next.first)) {
                    mLastTarget = next.first;
                    if (mLastTarget.mAccount != null) {
                        mOwnCloudClient = OwnCloudClientFactory.createOwnCloudClient(mLastTarget.mAccount, getApplicationContext());
                        mStorageManager = new FileDataStorageManager(mLastTarget.mAccount, getContentResolver());
                    } else {
                        mOwnCloudClient = OwnCloudClientFactory.createOwnCloudClient(mLastTarget.mServerUrl, getApplicationContext(), true);    // this is not good enough
                        mStorageManager = null;
                    }
                }

                /// perform the operation
                if (mCurrentOperation instanceof SyncOperation) {
                    result = ((SyncOperation)mCurrentOperation).execute(mOwnCloudClient, mStorageManager);
                } else {
                    result = mCurrentOperation.execute(mOwnCloudClient);
                }
            
            } catch (AccountsException e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Error while trying to get autorization for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Error while trying to get autorization for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
                
            } catch (IOException e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Error while trying to get autorization for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Error while trying to get autorization for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
            } catch (Exception e) {
                if (mLastTarget.mAccount == null) {
                    Log_OC.e(TAG, "Unexpected error for a NULL account", e);
                } else {
                    Log_OC.e(TAG, "Unexpected error for " + mLastTarget.mAccount.name, e);
                }
                result = new RemoteOperationResult(e);
            
            } finally {
                synchronized(mPendingOperations) {
                    mPendingOperations.poll();
                }
            }
            
            //sendBroadcastOperationFinished(mLastTarget, mCurrentOperation, result);
            callbackOperationListeners(mLastTarget, mCurrentOperation, result);
        }
    }


    /**
     * Sends a broadcast when a new operation is added to the queue.
     * 
     * Local broadcasts are only delivered to activities in the same process, but can't be done sticky :\
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Added operation.
     */
    private void sendBroadcastNewOperation(Target target, RemoteOperation operation) {
        Intent intent = new Intent(ACTION_OPERATION_ADDED);
        if (target.mAccount != null) {
            intent.putExtra(EXTRA_ACCOUNT, target.mAccount);    
        } else {
            intent.putExtra(EXTRA_SERVER_URL, target.mServerUrl);    
        }
        //LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        //lbm.sendBroadcast(intent);
        sendStickyBroadcast(intent);
    }

    
    // TODO - maybe add a notification for real start of operations
    
    /**
     * Sends a LOCAL broadcast when an operations finishes in order to the interested activities can update their view
     * 
     * Local broadcasts are only delivered to activities in the same process.
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Finished operation.
     * @param result            Result of the operation.
     */
    private void sendBroadcastOperationFinished(Target target, RemoteOperation operation, RemoteOperationResult result) {
        Intent intent = new Intent(ACTION_OPERATION_FINISHED);
        intent.putExtra(EXTRA_RESULT, result);
        if (target.mAccount != null) {
            intent.putExtra(EXTRA_ACCOUNT, target.mAccount);    
        } else {
            intent.putExtra(EXTRA_SERVER_URL, target.mServerUrl);    
        }
        //LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        //lbm.sendBroadcast(intent);
        sendStickyBroadcast(intent);
    }

    
    /**
     * Notifies the currently subscribed listeners about the end of an operation.
     * 
     * @param target            Account or URL pointing to an OC server.
     * @param operation         Finished operation.
     * @param result            Result of the operation.
     */
    private void callbackOperationListeners(Target target, final RemoteOperation operation, final RemoteOperationResult result) {
        Iterator<OnRemoteOperationListener> listeners = mBinder.mBoundListeners.keySet().iterator();
        while (listeners.hasNext()) {
            final OnRemoteOperationListener listener = listeners.next();
            final Handler handler = mBinder.mBoundListeners.get(listener);
            if (handler != null) { 
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRemoteOperationFinish(operation, result);
                    }
                });
            }
        }
            
    }
    

}
