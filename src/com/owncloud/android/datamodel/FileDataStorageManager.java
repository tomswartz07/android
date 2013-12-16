/* ownCloud Android client application
 *   Copyright (C) 2012  Bartek Przybylski
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

package com.owncloud.android.datamodel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Vector;

import com.owncloud.android.MainApp;
import com.owncloud.android.db.ProviderMeta.ProviderTableMeta;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.Log_OC;


import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;

public class FileDataStorageManager {

    public static final int ROOT_PARENT_ID = 0;

    private ContentResolver mContentResolver;
    private ContentProviderClient mContentProviderClient;
    private Account mAccount;

    private static String TAG = FileDataStorageManager.class.getSimpleName();

    
    public FileDataStorageManager(Account account, ContentResolver cr) {
        mContentProviderClient = null;
        mContentResolver = cr;
        mAccount = account;
    }

    public FileDataStorageManager(Account account, ContentProviderClient cp) {
        mContentProviderClient = cp;
        mContentResolver = null;
        mAccount = account;
    }

    
    public void setAccount(Account account) {
        mAccount = account;
    }

    public Account getAccount() {
        return mAccount;
    }

    public void setContentResolver(ContentResolver cr) {
        mContentResolver = cr;
    }

    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    public void setContentProviderClient(ContentProviderClient cp) {
        mContentProviderClient = cp;
    }

    public ContentProviderClient getContentProviderClient() {
        return mContentProviderClient;
    }
    

    public OCFile getFileByPath(String path) {
        Cursor c = getCursorForValue(ProviderTableMeta.FILE_PATH, path);
        OCFile file = null;
        if (c.moveToFirst()) {
            file = createFileInstance(c);
        }
        c.close();
        if (file == null && OCFile.ROOT_PATH.equals(path)) {
            return createRootDir(); // root should always exist
        }
        return file;
    }


    public OCFile getFileById(long id) {
        Cursor c = getCursorForValue(ProviderTableMeta._ID, String.valueOf(id));
        OCFile file = null;
        if (c.moveToFirst()) {
            file = createFileInstance(c);
        }
        c.close();
        return file;
    }

    public OCFile getFileByLocalPath(String path) {
        Cursor c = getCursorForValue(ProviderTableMeta.FILE_STORAGE_PATH, path);
        OCFile file = null;
        if (c.moveToFirst()) {
            file = createFileInstance(c);
        }
        c.close();
        return file;
    }

    public boolean fileExists(long id) {
        return fileExists(ProviderTableMeta._ID, String.valueOf(id));
    }

    public boolean fileExists(String path) {
        return fileExists(ProviderTableMeta.FILE_PATH, path);
    }

    
    public Vector<OCFile> getFolderContent(OCFile f) {
        if (f != null && f.isFolder() && f.getFileId() != -1) {
            return getFolderContent(f.getFileId());

        } else {
            return new Vector<OCFile>();
        }
    }
    
    
    public Vector<OCFile> getFolderImages(OCFile folder) {
        Vector<OCFile> ret = new Vector<OCFile>(); 
        if (folder != null) {
            // TODO better implementation, filtering in the access to database (if possible) instead of here 
            Vector<OCFile> tmp = getFolderContent(folder);
            OCFile current = null; 
            for (int i=0; i<tmp.size(); i++) {
                current = tmp.get(i);
                if (current.isImage()) {
                    ret.add(current);
                }
            }
        }
        return ret;
    }

    
    public boolean saveFile(OCFile file) {
        boolean overriden = false;
        ContentValues cv = new ContentValues();
        cv.put(ProviderTableMeta.FILE_MODIFIED, file.getModificationTimestamp());
        cv.put(ProviderTableMeta.FILE_MODIFIED_AT_LAST_SYNC_FOR_DATA, file.getModificationTimestampAtLastSyncForData());
        cv.put(ProviderTableMeta.FILE_CREATION, file.getCreationTimestamp());
        cv.put(ProviderTableMeta.FILE_CONTENT_LENGTH, file.getFileLength());
        cv.put(ProviderTableMeta.FILE_CONTENT_TYPE, file.getMimetype());
        cv.put(ProviderTableMeta.FILE_NAME, file.getFileName());
        //if (file.getParentId() != DataStorageManager.ROOT_PARENT_ID)
            cv.put(ProviderTableMeta.FILE_PARENT, file.getParentId());
        cv.put(ProviderTableMeta.FILE_PATH, file.getRemotePath());
        if (!file.isFolder())
            cv.put(ProviderTableMeta.FILE_STORAGE_PATH, file.getStoragePath());
        cv.put(ProviderTableMeta.FILE_ACCOUNT_OWNER, mAccount.name);
        cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE, file.getLastSyncDateForProperties());
        cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE_FOR_DATA, file.getLastSyncDateForData());
        cv.put(ProviderTableMeta.FILE_KEEP_IN_SYNC, file.keepInSync() ? 1 : 0);
        cv.put(ProviderTableMeta.FILE_ETAG, file.getEtag());

        boolean sameRemotePath = fileExists(file.getRemotePath());
        if (sameRemotePath ||
                fileExists(file.getFileId())        ) {           // for renamed files; no more delete and create

            OCFile oldFile = null;
            if (sameRemotePath) {
                oldFile = getFileByPath(file.getRemotePath());
                file.setFileId(oldFile.getFileId());
            } else {
                oldFile = getFileById(file.getFileId());
            }

            overriden = true;
            if (getContentResolver() != null) {
                getContentResolver().update(ProviderTableMeta.CONTENT_URI, cv,
                        ProviderTableMeta._ID + "=?",
                        new String[] { String.valueOf(file.getFileId()) });
            } else {
                try {
                    getContentProviderClient().update(ProviderTableMeta.CONTENT_URI,
                            cv, ProviderTableMeta._ID + "=?",
                            new String[] { String.valueOf(file.getFileId()) });
                } catch (RemoteException e) {
                    Log_OC.e(TAG,
                            "Fail to insert insert file to database "
                                    + e.getMessage());
                }
            }
        } else {
            Uri result_uri = null;
            if (getContentResolver() != null) {
                result_uri = getContentResolver().insert(
                        ProviderTableMeta.CONTENT_URI_FILE, cv);
            } else {
                try {
                    result_uri = getContentProviderClient().insert(
                            ProviderTableMeta.CONTENT_URI_FILE, cv);
                } catch (RemoteException e) {
                    Log_OC.e(TAG,
                            "Fail to insert insert file to database "
                                    + e.getMessage());
                }
            }
            if (result_uri != null) {
                long new_id = Long.parseLong(result_uri.getPathSegments()
                        .get(1));
                file.setFileId(new_id);
            }            
        }

        if (file.isFolder()) {
            updateFolderSize(file.getFileId());
        } else {
            updateFolderSize(file.getParentId());
        }
        
        return overriden;
    }


    /**
     * Inserts or updates the list of files contained in a given folder.
     * 
     * CALLER IS THE RESPONSIBLE FOR GRANTING RIGHT UPDATE OF INFORMATION, NOT THIS METHOD.
     * HERE ONLY DATA CONSISTENCY SHOULD BE GRANTED
     *  
     * @param folder
     * @param files
     * @param removeNotUpdated
     */
    public void saveFolder(OCFile folder, Collection<OCFile> updatedFiles, Collection<OCFile> filesToRemove) {
        
        Log_OC.d(TAG,  "Saving folder " + folder.getRemotePath() + " with " + updatedFiles.size() + " children and " + filesToRemove.size() + " files to remove");

        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>(updatedFiles.size());

        // prepare operations to insert or update files to save in the given folder
        for (OCFile file : updatedFiles) {
            ContentValues cv = new ContentValues();
            cv.put(ProviderTableMeta.FILE_MODIFIED, file.getModificationTimestamp());
            cv.put(ProviderTableMeta.FILE_MODIFIED_AT_LAST_SYNC_FOR_DATA, file.getModificationTimestampAtLastSyncForData());
            cv.put(ProviderTableMeta.FILE_CREATION, file.getCreationTimestamp());
            cv.put(ProviderTableMeta.FILE_CONTENT_LENGTH, file.getFileLength());
            cv.put(ProviderTableMeta.FILE_CONTENT_TYPE, file.getMimetype());
            cv.put(ProviderTableMeta.FILE_NAME, file.getFileName());
            //cv.put(ProviderTableMeta.FILE_PARENT, file.getParentId());
            cv.put(ProviderTableMeta.FILE_PARENT, folder.getFileId());
            cv.put(ProviderTableMeta.FILE_PATH, file.getRemotePath());
            if (!file.isFolder()) {
                cv.put(ProviderTableMeta.FILE_STORAGE_PATH, file.getStoragePath());
            }
            cv.put(ProviderTableMeta.FILE_ACCOUNT_OWNER, mAccount.name);
            cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE, file.getLastSyncDateForProperties());
            cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE_FOR_DATA, file.getLastSyncDateForData());
            cv.put(ProviderTableMeta.FILE_KEEP_IN_SYNC, file.keepInSync() ? 1 : 0);
            cv.put(ProviderTableMeta.FILE_ETAG, file.getEtag());

            boolean existsByPath = fileExists(file.getRemotePath());
            if (existsByPath || fileExists(file.getFileId())) {
                // updating an existing file
                operations.add(ContentProviderOperation.newUpdate(ProviderTableMeta.CONTENT_URI).
                        withValues(cv).
                        withSelection(  ProviderTableMeta._ID + "=?", 
                                new String[] { String.valueOf(file.getFileId()) })
                                .build());

            } else {
                // adding a new file
                operations.add(ContentProviderOperation.newInsert(ProviderTableMeta.CONTENT_URI).withValues(cv).build());
            }
        }
        
        // prepare operations to remove files in the given folder
        String where = ProviderTableMeta.FILE_ACCOUNT_OWNER + "=?" + " AND " + ProviderTableMeta.FILE_PATH + "=?";
        String [] whereArgs = null;
        for (OCFile file : filesToRemove) {
            if (file.getParentId() == folder.getFileId()) {
                whereArgs = new String[]{mAccount.name, file.getRemotePath()};
                //Uri.withAppendedPath(ProviderTableMeta.CONTENT_URI_FILE, "" + file.getFileId());
                if (file.isFolder()) {
                    operations.add(ContentProviderOperation
                                    .newDelete(ContentUris.withAppendedId(ProviderTableMeta.CONTENT_URI_DIR, file.getFileId())).withSelection(where, whereArgs)
                                        .build());
                    // TODO remove local folder
                } else {
                    operations.add(ContentProviderOperation
                                    .newDelete(ContentUris.withAppendedId(ProviderTableMeta.CONTENT_URI_FILE, file.getFileId())).withSelection(where, whereArgs)
                                        .build());
                    if (file.isDown()) {
                        new File(file.getStoragePath()).delete();
                        // TODO move the deletion of local contents after success of deletions
                    }
                }
            }
        }
        
        // update metadata of folder
        ContentValues cv = new ContentValues();
        cv.put(ProviderTableMeta.FILE_MODIFIED, folder.getModificationTimestamp());
        cv.put(ProviderTableMeta.FILE_MODIFIED_AT_LAST_SYNC_FOR_DATA, folder.getModificationTimestampAtLastSyncForData());
        cv.put(ProviderTableMeta.FILE_CREATION, folder.getCreationTimestamp());
        cv.put(ProviderTableMeta.FILE_CONTENT_LENGTH, 0);   // FileContentProvider calculates the right size
        cv.put(ProviderTableMeta.FILE_CONTENT_TYPE, folder.getMimetype());
        cv.put(ProviderTableMeta.FILE_NAME, folder.getFileName());
        cv.put(ProviderTableMeta.FILE_PARENT, folder.getParentId());
        cv.put(ProviderTableMeta.FILE_PATH, folder.getRemotePath());
        cv.put(ProviderTableMeta.FILE_ACCOUNT_OWNER, mAccount.name);
        cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE, folder.getLastSyncDateForProperties());
        cv.put(ProviderTableMeta.FILE_LAST_SYNC_DATE_FOR_DATA, folder.getLastSyncDateForData());
        cv.put(ProviderTableMeta.FILE_KEEP_IN_SYNC, folder.keepInSync() ? 1 : 0);
        cv.put(ProviderTableMeta.FILE_ETAG, folder.getEtag());
        operations.add(ContentProviderOperation.newUpdate(ProviderTableMeta.CONTENT_URI).
                withValues(cv).
                withSelection(  ProviderTableMeta._ID + "=?", 
                        new String[] { String.valueOf(folder.getFileId()) })
                        .build());

        // apply operations in batch
        ContentProviderResult[] results = null;
        Log_OC.d(TAG, "Sending " + operations.size() + " operations to FileContentProvider");
        try {
            if (getContentResolver() != null) {
                results = getContentResolver().applyBatch(MainApp.getAuthority(), operations);

            } else {
                results = getContentProviderClient().applyBatch(operations);
            }

        } catch (OperationApplicationException e) {
            Log_OC.e(TAG, "Exception in batch of operations " + e.getMessage());

        } catch (RemoteException e) {
            Log_OC.e(TAG, "Exception in batch of operations  " + e.getMessage());
        }

        // update new id in file objects for insertions
        if (results != null) {
            long newId;
            Iterator<OCFile> filesIt = updatedFiles.iterator();
            OCFile file = null;
            for (int i=0; i<results.length; i++) {
                if (filesIt.hasNext()) {
                    file = filesIt.next();
                } else {
                    file = null;
                }
                if (results[i].uri != null) {
                    newId = Long.parseLong(results[i].uri.getPathSegments().get(1));
                    //updatedFiles.get(i).setFileId(newId);
                    if (file != null) {
                        file.setFileId(newId);
                    }
                }
            }
        }
        
        updateFolderSize(folder.getFileId());
        
    }


    /**
     * 
     * @param id
     */
    private void updateFolderSize(long id) {
        if (id > FileDataStorageManager.ROOT_PARENT_ID) {
            Log_OC.d(TAG, "Updating size of " + id);
            if (getContentResolver() != null) {
                getContentResolver().update(ProviderTableMeta.CONTENT_URI_DIR, 
                        new ContentValues(),    // won't be used, but cannot be null; crashes in KLP
                        ProviderTableMeta._ID + "=?",
                        new String[] { String.valueOf(id) });
            } else {
                try {
                    getContentProviderClient().update(ProviderTableMeta.CONTENT_URI_DIR, 
                            new ContentValues(),    // won't be used, but cannot be null; crashes in KLP
                            ProviderTableMeta._ID + "=?",
                            new String[] { String.valueOf(id) });
                    
                } catch (RemoteException e) {
                    Log_OC.e(TAG, "Exception in update of folder size through compatibility patch " + e.getMessage());
                }
            }
        } else {
            Log_OC.e(TAG,  "not updating size for folder " + id);
        }
    }
    

    public void removeFile(OCFile file, boolean removeDBData, boolean removeLocalCopy) {
        if (file != null) {
            if (file.isFolder()) {
                removeFolder(file, removeDBData, removeLocalCopy);
                
            } else {
                if (removeDBData) {
                    //Uri file_uri = Uri.withAppendedPath(ProviderTableMeta.CONTENT_URI_FILE, ""+file.getFileId());
                    Uri file_uri = ContentUris.withAppendedId(ProviderTableMeta.CONTENT_URI_FILE, file.getFileId());
                    String where = ProviderTableMeta.FILE_ACCOUNT_OWNER + "=?" + " AND " + ProviderTableMeta.FILE_PATH + "=?";
                    String [] whereArgs = new String[]{mAccount.name, file.getRemotePath()};
                    if (getContentProviderClient() != null) {
                        try {
                            getContentProviderClient().delete(file_uri, where, whereArgs);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else {
                        getContentResolver().delete(file_uri, where, whereArgs);
                    }
                    updateFolderSize(file.getParentId());
                }
                if (removeLocalCopy && file.isDown() && file.getStoragePath() != null) {
                    boolean success = new File(file.getStoragePath()).delete();
                    if (!removeDBData && success) {
                        // maybe unnecessary, but should be checked TODO remove if unnecessary
                        file.setStoragePath(null);
                        saveFile(file);
                    }
                }
            }
        }
    }
    

    public void removeFolder(OCFile folder, boolean removeDBData, boolean removeLocalContent) {
        if (folder != null && folder.isFolder()) {
            if (removeDBData &&  folder.getFileId() != -1) {
                removeFolderInDb(folder);
            }
            if (removeLocalContent) {
                File localFolder = new File(FileStorageUtils.getDefaultSavePathFor(mAccount.name, folder));
                removeLocalFolder(localFolder);
            }
        }
    }

    private void removeFolderInDb(OCFile folder) {
        Uri folder_uri = Uri.withAppendedPath(ProviderTableMeta.CONTENT_URI_DIR, ""+ folder.getFileId());   // URI for recursive deletion
        String where = ProviderTableMeta.FILE_ACCOUNT_OWNER + "=?" + " AND " + ProviderTableMeta.FILE_PATH + "=?";
        String [] whereArgs = new String[]{mAccount.name, folder.getRemotePath()};
        if (getContentProviderClient() != null) {
            try {
                getContentProviderClient().delete(folder_uri, where, whereArgs);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else {
            getContentResolver().delete(folder_uri, where, whereArgs); 
        }
        updateFolderSize(folder.getParentId());
    }

    private void removeLocalFolder(File folder) {
        if (folder.exists()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        removeLocalFolder(file);
                    } else {
                        file.delete();
                    }
                }
            }
            folder.delete();
        }
    }

    /**
     * Updates database for a folder that was moved to a different location.
     * 
     * TODO explore better (faster) implementations
     * TODO throw exceptions up !
     */
    public void moveFolder(OCFile folder, String newPath) {
        // TODO check newPath

        if (folder != null && folder.isFolder() && folder.fileExists() && !OCFile.ROOT_PATH.equals(folder.getFileName())) {
            /// 1. get all the descendants of 'dir' in a single QUERY (including 'dir')
            Cursor c = null;
            if (getContentProviderClient() != null) {
                try {
                    c = getContentProviderClient().query(ProviderTableMeta.CONTENT_URI, 
                            null,
                            ProviderTableMeta.FILE_ACCOUNT_OWNER + "=? AND " + ProviderTableMeta.FILE_PATH + " LIKE ? ",
                            new String[] { mAccount.name, folder.getRemotePath() + "%"  }, ProviderTableMeta.FILE_PATH + " ASC ");
                } catch (RemoteException e) {
                    Log_OC.e(TAG, e.getMessage());
                }
            } else {
                c = getContentResolver().query(ProviderTableMeta.CONTENT_URI, 
                        null,
                        ProviderTableMeta.FILE_ACCOUNT_OWNER + "=? AND " + ProviderTableMeta.FILE_PATH + " LIKE ? ",
                        new String[] { mAccount.name, folder.getRemotePath() + "%"  }, ProviderTableMeta.FILE_PATH + " ASC ");
            }

            /// 2. prepare a batch of update operations to change all the descendants
            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>(c.getCount());
            int lengthOfOldPath = folder.getRemotePath().length();
            String defaultSavePath = FileStorageUtils.getSavePath(mAccount.name);
            int lengthOfOldStoragePath = defaultSavePath.length() + lengthOfOldPath;
            if (c.moveToFirst()) {
                do {
                    ContentValues cv = new ContentValues(); // don't take the constructor out of the loop and clear the object
                    OCFile child = createFileInstance(c);
                    cv.put(ProviderTableMeta.FILE_PATH, newPath + child.getRemotePath().substring(lengthOfOldPath));
                    if (child.getStoragePath() != null && child.getStoragePath().startsWith(defaultSavePath)) {
                        cv.put(ProviderTableMeta.FILE_STORAGE_PATH, defaultSavePath + newPath + child.getStoragePath().substring(lengthOfOldStoragePath));
                    }
                    operations.add(ContentProviderOperation.newUpdate(ProviderTableMeta.CONTENT_URI).
                            withValues(cv).
                            withSelection(  ProviderTableMeta._ID + "=?", 
                                    new String[] { String.valueOf(child.getFileId()) })
                                    .build());
                } while (c.moveToNext());
            }
            c.close();

            /// 3. apply updates in batch
            try {
                if (getContentResolver() != null) {
                    getContentResolver().applyBatch(MainApp.getAuthority(), operations);

                } else {
                    getContentProviderClient().applyBatch(operations);
                }

            } catch (OperationApplicationException e) {
                Log_OC.e(TAG, "Fail to update descendants of " + folder.getFileId() + " in database", e);

            } catch (RemoteException e) {
                Log_OC.e(TAG, "Fail to update desendants of " + folder.getFileId() + " in database", e);
            }

        }
    }

    
    private Vector<OCFile> getFolderContent(long parentId) {

        Vector<OCFile> ret = new Vector<OCFile>();

        Uri req_uri = Uri.withAppendedPath(
                ProviderTableMeta.CONTENT_URI_DIR,
                String.valueOf(parentId));
        Cursor c = null;

        if (getContentProviderClient() != null) {
            try {
                c = getContentProviderClient().query(req_uri, null, 
                        ProviderTableMeta.FILE_PARENT + "=?" ,
                        new String[] { String.valueOf(parentId)}, null);
            } catch (RemoteException e) {
                Log_OC.e(TAG, e.getMessage());
                return ret;
            }
        } else {
            c = getContentResolver().query(req_uri, null, 
                    ProviderTableMeta.FILE_PARENT + "=?" ,
                    new String[] { String.valueOf(parentId)}, null);
        }

        if (c.moveToFirst()) {
            do {
                OCFile child = createFileInstance(c);
                ret.add(child);
            } while (c.moveToNext());
        }

        c.close();

        Collections.sort(ret);

        return ret;
    }
    
    
    private OCFile createRootDir() {
        OCFile file = new OCFile(OCFile.ROOT_PATH);
        file.setMimetype("DIR");
        file.setParentId(FileDataStorageManager.ROOT_PARENT_ID);
        saveFile(file);
        return file;
    }

    private boolean fileExists(String cmp_key, String value) {
        Cursor c;
        if (getContentResolver() != null) {
            c = getContentResolver()
                    .query(ProviderTableMeta.CONTENT_URI,
                            null,
                            cmp_key + "=? AND "
                                    + ProviderTableMeta.FILE_ACCOUNT_OWNER
                                    + "=?",
                                    new String[] { value, mAccount.name }, null);
        } else {
            try {
                c = getContentProviderClient().query(
                        ProviderTableMeta.CONTENT_URI,
                        null,
                        cmp_key + "=? AND "
                                + ProviderTableMeta.FILE_ACCOUNT_OWNER + "=?",
                                new String[] { value, mAccount.name }, null);
            } catch (RemoteException e) {
                Log_OC.e(TAG,
                        "Couldn't determine file existance, assuming non existance: "
                                + e.getMessage());
                return false;
            }
        }
        boolean retval = c.moveToFirst();
        c.close();
        return retval;
    }

    private Cursor getCursorForValue(String key, String value) {
        Cursor c = null;
        if (getContentResolver() != null) {
            c = getContentResolver()
                    .query(ProviderTableMeta.CONTENT_URI,
                            null,
                            key + "=? AND "
                                    + ProviderTableMeta.FILE_ACCOUNT_OWNER
                                    + "=?",
                                    new String[] { value, mAccount.name }, null);
        } else {
            try {
                c = getContentProviderClient().query(
                        ProviderTableMeta.CONTENT_URI,
                        null,
                        key + "=? AND " + ProviderTableMeta.FILE_ACCOUNT_OWNER
                        + "=?", new String[] { value, mAccount.name },
                        null);
            } catch (RemoteException e) {
                Log_OC.e(TAG, "Could not get file details: " + e.getMessage());
                c = null;
            }
        }
        return c;
    }

    private OCFile createFileInstance(Cursor c) {
        OCFile file = null;
        if (c != null) {
            file = new OCFile(c.getString(c
                    .getColumnIndex(ProviderTableMeta.FILE_PATH)));
            file.setFileId(c.getLong(c.getColumnIndex(ProviderTableMeta._ID)));
            file.setParentId(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_PARENT)));
            file.setMimetype(c.getString(c
                    .getColumnIndex(ProviderTableMeta.FILE_CONTENT_TYPE)));
            if (!file.isFolder()) {
                file.setStoragePath(c.getString(c
                        .getColumnIndex(ProviderTableMeta.FILE_STORAGE_PATH)));
                if (file.getStoragePath() == null) {
                    // try to find existing file and bind it with current account; - with the current update of SynchronizeFolderOperation, this won't be necessary anymore after a full synchronization of the account
                    File f = new File(FileStorageUtils.getDefaultSavePathFor(mAccount.name, file));
                    if (f.exists()) {
                        file.setStoragePath(f.getAbsolutePath());
                        file.setLastSyncDateForData(f.lastModified());
                    }
                }
            }
            file.setFileLength(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_CONTENT_LENGTH)));
            file.setCreationTimestamp(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_CREATION)));
            file.setModificationTimestamp(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_MODIFIED)));
            file.setModificationTimestampAtLastSyncForData(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_MODIFIED_AT_LAST_SYNC_FOR_DATA)));
            file.setLastSyncDateForProperties(c.getLong(c
                    .getColumnIndex(ProviderTableMeta.FILE_LAST_SYNC_DATE)));
            file.setLastSyncDateForData(c.getLong(c.
                    getColumnIndex(ProviderTableMeta.FILE_LAST_SYNC_DATE_FOR_DATA)));
            file.setKeepInSync(c.getInt(
                    c.getColumnIndex(ProviderTableMeta.FILE_KEEP_IN_SYNC)) == 1 ? true : false);
            file.setEtag(c.getString(c.getColumnIndex(ProviderTableMeta.FILE_ETAG)));
                    
        }
        return file;
    }

}
