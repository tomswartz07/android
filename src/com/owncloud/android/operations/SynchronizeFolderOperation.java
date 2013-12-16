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

package com.owncloud.android.operations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.apache.http.HttpStatus;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;

import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.oc_framework.network.webdav.WebdavClient;
import com.owncloud.android.oc_framework.network.webdav.WebdavEntry;
import com.owncloud.android.oc_framework.network.webdav.WebdavUtils;
import com.owncloud.android.oc_framework.operations.RemoteOperation;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult;
import com.owncloud.android.oc_framework.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.oc_framework.operations.remote.ReadRemoteFolderOperation;
import com.owncloud.android.oc_framework.operations.RemoteFile;
import com.owncloud.android.syncadapter.FileSyncService;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.Log_OC;



/**
 *  Remote operation performing the synchronization of the list of files contained 
 *  in a folder identified with its remote path.
 *  
 *  Fetches the list and properties of the files contained in the given folder, including their 
 *  properties, and updates the local database with them.
 *  
 *  Does NOT enter in the child folders to synchronize their contents also.
 * 
 *  @author David A. Velasco
 */
public class SynchronizeFolderOperation extends RemoteOperation {

    private static final String TAG = SynchronizeFolderOperation.class.getSimpleName();

    
    /** Time stamp for the synchronization process in progress */
    private long mCurrentSyncTime;
    
    /** Remote folder to synchronize */
    private OCFile mLocalFolder;
    
    /** Access to the local database */
    private FileDataStorageManager mStorageManager;
    
    /** Account where the file to synchronize belongs */
    private Account mAccount;
    
    /** Android context; necessary to send requests to the download service */
    private Context mContext;
    
    /** Files and folders contained in the synchronized folder after a successful operation */
    private List<OCFile> mChildren;

    /** Counter of conflicts found between local and remote files */
    private int mConflictsFound;

    /** Counter of failed operations in synchronization of kept-in-sync files */
    private int mFailsInFavouritesFound;

    /** Map of remote and local paths to files that where locally stored in a location out of the ownCloud folder and couldn't be copied automatically into it */
    private Map<String, String> mForgottenLocalFiles;

    /** 'True' means that this operation is part of a full account synchronization */ 
    private boolean mSyncFullAccount;

    /** 'True' means that the remote folder changed from last synchronization and should be fetched */
    private boolean mRemoteFolderChanged;
    
    
    /**
     * Creates a new instance of {@link SynchronizeFolderOperation}.
     * 
     * @param   remoteFolderPath        Remote folder to synchronize.
     * @param   currentSyncTime         Time stamp for the synchronization process in progress.
     * @param   localFolderId           Identifier in the local database of the folder to synchronize.
     * @param   updateFolderProperties  'True' means that the properties of the folder should be updated also, not just its content.
     * @param   syncFullAccount         'True' means that this operation is part of a full account synchronization.
     * @param   dataStorageManager      Interface with the local database.
     * @param   account                 ownCloud account where the folder is located. 
     * @param   context                 Application context.
     */
    public SynchronizeFolderOperation(  OCFile folder, 
                                        long currentSyncTime, 
                                        boolean syncFullAccount,
                                        FileDataStorageManager dataStorageManager, 
                                        Account account, 
                                        Context context ) {
        mLocalFolder = folder;
        mCurrentSyncTime = currentSyncTime;
        mSyncFullAccount = syncFullAccount;
        mStorageManager = dataStorageManager;
        mAccount = account;
        mContext = context;
        mForgottenLocalFiles = new HashMap<String, String>();
        mRemoteFolderChanged = false;
    }
    
    
    public int getConflictsFound() {
        return mConflictsFound;
    }
    
    public int getFailsInFavouritesFound() {
        return mFailsInFavouritesFound;
    }
    
    public Map<String, String> getForgottenLocalFiles() {
        return mForgottenLocalFiles;
    }
    
    /**
     * Returns the list of files and folders contained in the synchronized folder, if called after synchronization is complete.
     * 
     * @return  List of files and folders contained in the synchronized folder.
     */
    public List<OCFile> getChildren() {
        return mChildren;
    }
    
    /**
     * Performs the synchronization.
     * 
     * {@inheritDoc}
     */
    @Override
    protected RemoteOperationResult run(WebdavClient client) {
        RemoteOperationResult result = null;
        mFailsInFavouritesFound = 0;
        mConflictsFound = 0;
        mForgottenLocalFiles.clear();
        
        result = checkForChanges(client);
        
        if (result.isSuccess()) {
            if (mRemoteFolderChanged) {
                result = fetchAndSyncRemoteFolder(client);
            } else {
                mChildren = mStorageManager.getFolderContent(mLocalFolder);
            }
        }
        
        if (!mSyncFullAccount) {            
            sendStickyBroadcast(false, mLocalFolder.getRemotePath(), result);
        }

        return result;
        
    }


    private RemoteOperationResult checkForChanges(WebdavClient client) {
        mRemoteFolderChanged = false;
        RemoteOperationResult result = null;
        String remotePath = null;
        PropFindMethod query = null;
        
        try {
            remotePath = mLocalFolder.getRemotePath();
            Log_OC.d(TAG, "Checking changes in " + mAccount.name + remotePath);

            // remote request 
            query = new PropFindMethod(client.getBaseUri() + WebdavUtils.encodePath(remotePath),
                    DavConstants.PROPFIND_ALL_PROP,
                    DavConstants.DEPTH_0);
            int status = client.executeMethod(query);

            // check and process response
            if (isMultiStatus(status)) {
                // parse data from remote folder 
                WebdavEntry we = new WebdavEntry(query.getResponseBodyAsMultiStatus().getResponses()[0], client.getBaseUri().getPath());
                OCFile remoteFolder = fillOCFile(we);
                
                // check if remote and local folder are different
                mRemoteFolderChanged = !(remoteFolder.getEtag().equalsIgnoreCase(mLocalFolder.getEtag()));
                
                result = new RemoteOperationResult(ResultCode.OK);
                
            } else {
                // check failed
                client.exhaustResponse(query.getResponseBodyAsStream());
                if (status == HttpStatus.SC_NOT_FOUND) {
                    removeLocalFolder();
                }
                result = new RemoteOperationResult(false, status, query.getResponseHeaders());
            }

        } catch (Exception e) {
            result = new RemoteOperationResult(e);
            

        } finally {
            if (query != null)
                query.releaseConnection();  // let the connection available for other methods
            if (result.isSuccess()) {
                Log_OC.i(TAG, "Checked " + mAccount.name + remotePath + " : " + (mRemoteFolderChanged ? "changed" : "not changed"));
            } else {
                if (result.isException()) {
                    Log_OC.e(TAG, "Checked " + mAccount.name + remotePath  + " : " + result.getLogMessage(), result.getException());
                } else {
                    Log_OC.e(TAG, "Checked " + mAccount.name + remotePath + " : " + result.getLogMessage());
                }
            }
            
        }
        return result;
    }


    private RemoteOperationResult fetchAndSyncRemoteFolder(WebdavClient client) {
        String remotePath = mLocalFolder.getRemotePath();
        ReadRemoteFolderOperation operation = new ReadRemoteFolderOperation(remotePath);
        RemoteOperationResult result = operation.execute(client);
        Log_OC.d(TAG, "Synchronizing " + mAccount.name + remotePath);
        
        if (result.isSuccess()) {
            synchronizeData(result.getData(), client);
            if (mConflictsFound > 0  || mFailsInFavouritesFound > 0) { 
                result = new RemoteOperationResult(ResultCode.SYNC_CONFLICT);   // should be different result, but will do the job
            }
        } else {
            if (result.getCode() == ResultCode.FILE_NOT_FOUND)
                removeLocalFolder();
        }
        
        return result;
    }

    
    private void removeLocalFolder() {
        if (mStorageManager.fileExists(mLocalFolder.getFileId())) {
            String currentSavePath = FileStorageUtils.getSavePath(mAccount.name);
            mStorageManager.removeFolder(mLocalFolder, true, (mLocalFolder.isDown() && mLocalFolder.getStoragePath().startsWith(currentSavePath)));
        }
    }


    /**
     *  Synchronizes the data retrieved from the server about the contents of the target folder 
     *  with the current data in the local database.
     *  
     *  Grants that mChildren is updated with fresh data after execution.
     *  
     *  @param folderAndFiles   Remote folder and children files in Folder 
     *  
     *  @param client           Client instance to the remote server where the data were 
     *                          retrieved.  
     *  @return                 'True' when any change was made in the local data, 'false' otherwise.
     */
    private void synchronizeData(ArrayList<RemoteFile> folderAndFiles, WebdavClient client) {
        // get 'fresh data' from the database
        mLocalFolder = mStorageManager.getFileByPath(mLocalFolder.getRemotePath());
        
        // parse data from remote folder 
        OCFile remoteFolder = fillOCFile(folderAndFiles.get(0));
        remoteFolder.setParentId(mLocalFolder.getParentId());
        remoteFolder.setFileId(mLocalFolder.getFileId());
        
        Log_OC.d(TAG, "Remote folder " + mLocalFolder.getRemotePath() + " changed - starting update of local data ");
        
        List<OCFile> updatedFiles = new Vector<OCFile>(folderAndFiles.size() - 1);
        List<SynchronizeFileOperation> filesToSyncContents = new Vector<SynchronizeFileOperation>();

        // get current data about local contents of the folder to synchronize
        List<OCFile> localFiles = mStorageManager.getFolderContent(mLocalFolder);
        Map<String, OCFile> localFilesMap = new HashMap<String, OCFile>(localFiles.size());
        for (OCFile file : localFiles) {
            localFilesMap.put(file.getRemotePath(), file);
        }
        
        // loop to update every child
        OCFile remoteFile = null, localFile = null;
        for (int i=1; i<folderAndFiles.size(); i++) {
            /// new OCFile instance with the data from the server
            remoteFile = fillOCFile(folderAndFiles.get(i));
            remoteFile.setParentId(mLocalFolder.getFileId());

            /// retrieve local data for the read file 
            //localFile = mStorageManager.getFileByPath(remoteFile.getRemotePath());
            localFile = localFilesMap.remove(remoteFile.getRemotePath());
            
            /// add to the remoteFile (the new one) data about LOCAL STATE (not existing in the server side)
            remoteFile.setLastSyncDateForProperties(mCurrentSyncTime);
            if (localFile != null) {
                // some properties of local state are kept unmodified
                remoteFile.setFileId(localFile.getFileId());
                remoteFile.setKeepInSync(localFile.keepInSync());
                remoteFile.setLastSyncDateForData(localFile.getLastSyncDateForData());
                remoteFile.setModificationTimestampAtLastSyncForData(localFile.getModificationTimestampAtLastSyncForData());
                remoteFile.setStoragePath(localFile.getStoragePath());
                remoteFile.setEtag(localFile.getEtag());    // eTag will not be updated unless contents are synchronized (Synchronize[File|Folder]Operation with remoteFile as parameter)
                if (remoteFile.isFolder()) {
                    remoteFile.setFileLength(localFile.getFileLength()); // TODO move operations about size of folders to FileContentProvider
                }
            } else {
                remoteFile.setEtag(""); // remote eTag will not be updated unless contents are synchronized (Synchronize[File|Folder]Operation with remoteFile as parameter)
            }

            /// check and fix, if needed, local storage path
            checkAndFixForeignStoragePath(remoteFile);      // fixing old policy - now local files must be copied into the ownCloud local folder 
            searchForLocalFileInDefaultPath(remoteFile);    // legacy   

            /// prepare content synchronization for kept-in-sync files
            if (remoteFile.keepInSync()) {
                SynchronizeFileOperation operation = new SynchronizeFileOperation(  localFile,        
                                                                                    remoteFile, 
                                                                                    mStorageManager,
                                                                                    mAccount,       
                                                                                    true, 
                                                                                    mContext
                                                                                    );
                filesToSyncContents.add(operation);
            }
            
            updatedFiles.add(remoteFile);
        }

        // save updated contents in local database; all at once, trying to get a best performance in database update (not a big deal, indeed)
        mStorageManager.saveFolder(remoteFolder, updatedFiles, localFilesMap.values());

        // request for the synchronization of file contents AFTER saving current remote properties
        startContentSynchronizations(filesToSyncContents, client);

        // removal of obsolete files
        //removeObsoleteFiles();
       
        // must be done AFTER saving all the children information, so that eTag is not updated in the database in case of unexpected exceptions
        //mStorageManager.saveFile(remoteFolder);
        mChildren = updatedFiles;
        
    }

    /**
     * Performs a list of synchronization operations, determining if a download or upload is needed or
     * if exists conflict due to changes both in local and remote contents of the each file.
     * 
     * If download or upload is needed, request the operation to the corresponding service and goes on.
     * 
     * @param filesToSyncContents       Synchronization operations to execute.
     * @param client                    Interface to the remote ownCloud server.
     */
    private void startContentSynchronizations(List<SynchronizeFileOperation> filesToSyncContents, WebdavClient client) {
        RemoteOperationResult contentsResult = null;
        for (SynchronizeFileOperation op: filesToSyncContents) {
            contentsResult = op.execute(client);   // returns without waiting for upload or download finishes
            if (!contentsResult.isSuccess()) {
                if (contentsResult.getCode() == ResultCode.SYNC_CONFLICT) {
                    mConflictsFound++;
                } else {
                    mFailsInFavouritesFound++;
                    if (contentsResult.getException() != null) {
                        Log_OC.e(TAG, "Error while synchronizing favourites : " +  contentsResult.getLogMessage(), contentsResult.getException());
                    } else {
                        Log_OC.e(TAG, "Error while synchronizing favourites : " + contentsResult.getLogMessage());
                    }
                }
            }   // won't let these fails break the synchronization process
        }
    }


    public boolean isMultiStatus(int status) {
        return (status == HttpStatus.SC_MULTI_STATUS); 
    }

    
    /**
     * Creates and populates a new {@link OCFile} object with the data read from the server.
     * 
     * @param we        WebDAV entry read from the server for a WebDAV resource (remote file or folder).
     * @return          New OCFile instance representing the remote resource described by we.
     */
    private OCFile fillOCFile(WebdavEntry we) {
        OCFile file = new OCFile(we.decodedPath());
        file.setCreationTimestamp(we.createTimestamp());
        file.setFileLength(we.contentLength());
        file.setMimetype(we.contentType());
        file.setModificationTimestamp(we.modifiedTimestamp());
        file.setEtag(we.etag());
        return file;
    }

    /**
     * Creates and populates a new {@link OCFile} object with the data read from the server.
     * 
     * @param remote    remote file read from the server (remote file or folder).
     * @return          New OCFile instance representing the remote resource described by we.
     */
    private OCFile fillOCFile(RemoteFile remote) {
        OCFile file = new OCFile(remote.getRemotePath());
        file.setCreationTimestamp(remote.getCreationTimestamp());
        file.setFileLength(remote.getLength());
        file.setMimetype(remote.getMimeType());
        file.setModificationTimestamp(remote.getModifiedTimestamp());
        file.setEtag(remote.getEtag());
        return file;
    }
    

    /**
     * Checks the storage path of the OCFile received as parameter. If it's out of the local ownCloud folder,
     * tries to copy the file inside it. 
     * 
     * If the copy fails, the link to the local file is nullified. The account of forgotten files is kept in 
     * {@link #mForgottenLocalFiles}
     *) 
     * @param file      File to check and fix.
     */
    private void checkAndFixForeignStoragePath(OCFile file) {
        String storagePath = file.getStoragePath();
        String expectedPath = FileStorageUtils.getDefaultSavePathFor(mAccount.name, file);
        if (storagePath != null && !storagePath.equals(expectedPath)) {
            /// fix storagePaths out of the local ownCloud folder
            File originalFile = new File(storagePath);
            if (FileStorageUtils.getUsableSpace(mAccount.name) < originalFile.length()) {
                mForgottenLocalFiles.put(file.getRemotePath(), storagePath);
                file.setStoragePath(null);
                    
            } else {
                InputStream in = null;
                OutputStream out = null;
                try {
                    File expectedFile = new File(expectedPath);
                    File expectedParent = expectedFile.getParentFile();
                    expectedParent.mkdirs();
                    if (!expectedParent.isDirectory()) {
                        throw new IOException("Unexpected error: parent directory could not be created");
                    }
                    expectedFile.createNewFile();
                    if (!expectedFile.isFile()) {
                        throw new IOException("Unexpected error: target file could not be created");
                    }                    
                    in = new FileInputStream(originalFile);
                    out = new FileOutputStream(expectedFile);
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) > 0){
                        out.write(buf, 0, len);
                    }
                    file.setStoragePath(expectedPath);
                    
                } catch (Exception e) {
                    Log_OC.e(TAG, "Exception while copying foreign file " + expectedPath, e);
                    mForgottenLocalFiles.put(file.getRemotePath(), storagePath);
                    file.setStoragePath(null);
                    
                } finally {
                    try {
                        if (in != null) in.close();
                    } catch (Exception e) {
                        Log_OC.d(TAG, "Weird exception while closing input stream for " + storagePath + " (ignoring)", e);
                    }
                    try {
                        if (out != null) out.close();
                    } catch (Exception e) {
                        Log_OC.d(TAG, "Weird exception while closing output stream for " + expectedPath + " (ignoring)", e);
                    }
                }
            }
        }
    }

    /**
     * Scans the default location for saving local copies of files searching for
     * a 'lost' file with the same full name as the {@link OCFile} received as 
     * parameter.
     *  
     * @param file      File to associate a possible 'lost' local file.
     */
    private void searchForLocalFileInDefaultPath(OCFile file) {
        if (file.getStoragePath() == null && !file.isFolder()) {
            File f = new File(FileStorageUtils.getDefaultSavePathFor(mAccount.name, file));
            if (f.exists()) {
                file.setStoragePath(f.getAbsolutePath());
                file.setLastSyncDateForData(f.lastModified());
            }
        }
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
        i.putExtra(FileSyncService.ACCOUNT_NAME, mAccount.name);
        if (dirRemotePath != null) {
            i.putExtra(FileSyncService.SYNC_FOLDER_REMOTE_PATH, dirRemotePath);
        }
        if (result != null) {
            i.putExtra(FileSyncService.SYNC_RESULT, result);
        }
        mContext.sendStickyBroadcast(i);
    }


    public boolean getRemoteFolderChanged() {
        return mRemoteFolderChanged;
    }

}
