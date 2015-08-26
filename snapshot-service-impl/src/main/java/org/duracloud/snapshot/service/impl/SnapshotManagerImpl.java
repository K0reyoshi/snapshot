/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.snapshot.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.duracloud.client.ContentStore;
import org.duracloud.client.task.SnapshotTaskClient;
import org.duracloud.common.constant.Constants;
import org.duracloud.common.notification.NotificationManager;
import org.duracloud.common.notification.NotificationType;
import org.duracloud.common.util.ChecksumUtil;
import org.duracloud.common.util.ChecksumUtil.Algorithm;
import org.duracloud.common.util.IOUtil;
import org.duracloud.snapshot.SnapshotException;
import org.duracloud.snapshot.SnapshotNotFoundException;
import org.duracloud.snapshot.common.SnapshotServiceConstants;
import org.duracloud.snapshot.db.ContentDirUtils;
import org.duracloud.snapshot.db.model.DuracloudEndPointConfig;
import org.duracloud.snapshot.db.model.Snapshot;
import org.duracloud.snapshot.db.model.SnapshotContentItem;
import org.duracloud.snapshot.db.model.SnapshotHistory;
import org.duracloud.snapshot.db.repo.SnapshotContentItemRepo;
import org.duracloud.snapshot.db.repo.SnapshotRepo;
import org.duracloud.snapshot.dto.SnapshotStatus;
import org.duracloud.snapshot.dto.task.CompleteSnapshotTaskResult;
import org.duracloud.snapshot.service.AlternateIdAlreadyExistsException;
import org.duracloud.snapshot.service.BridgeConfiguration;
import org.duracloud.snapshot.service.SnapshotManager;
import org.duracloud.snapshot.service.SnapshotManagerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Daniel Bernstein Date: Jul 31, 2014
 */
@Component
public class SnapshotManagerImpl implements SnapshotManager {
    private static Logger log = LoggerFactory.getLogger(SnapshotManagerImpl.class);

    protected static String[] METADATA_FILENAMES = {Constants.SNAPSHOT_PROPS_FILENAME, 
        SnapshotServiceConstants.CONTENT_PROPERTIES_JSON_FILENAME, 
        SnapshotServiceConstants.MANIFEST_MD5_TXT_FILE_NAME,
        SnapshotServiceConstants.MANIFEST_SHA256_TXT_FILE_NAME};
    
    //NOTE: auto wiring at the field level rather than in the constructor seems to be necessary
    //      when annotating methods with @Transactional.
    @Autowired
    private SnapshotContentItemRepo snapshotContentItemRepo;

    @Autowired
    private SnapshotRepo snapshotRepo;

    @Autowired
    private NotificationManager notificationManager;

    @Autowired
    private SnapshotTaskClientHelper snapshotTaskClientHelper;
    
    @Autowired 
    private StoreClientHelper storeClientHelper;
    
    private ChecksumUtil checksumUtil;

    @Autowired
    private BridgeConfiguration bridgeConfig;

    public SnapshotManagerImpl() {
        this.checksumUtil = new ChecksumUtil(Algorithm.MD5);
    }

    /**
     * @param snapshotContentItemRepo the snapshotContentItemRepo to set
     */
    public void setSnapshotContentItemRepo(SnapshotContentItemRepo snapshotContentItemRepo) {
        this.snapshotContentItemRepo = snapshotContentItemRepo;
    }

    /**
     * @param snapshotRepo the snapshotRepo to set
     */
    public void setSnapshotRepo(SnapshotRepo snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    /**
     * @param notificationManager the notificationManager to set
     */
    public void setNotificationManager(NotificationManager notificationManager) {
        this.notificationManager = notificationManager;
    }

    /**
     * @param snapshotTaskClientHelper the snapshotTaskClientHelper to set
     */
    public void setSnapshotTaskClientHelper(SnapshotTaskClientHelper snapshotTaskClientHelper) {
        this.snapshotTaskClientHelper = snapshotTaskClientHelper;
    }

    /**
     * @param bridgeConfig the bridgeConfig to set
     */
    public void setBridgeConfig(BridgeConfiguration bridgeConfig) {
        this.bridgeConfig = bridgeConfig;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.duracloud.snapshot.service.SnapshotManager#addContentItem(org.duracloud
     * .snapshot.db.model.Snapshot, java.lang.String, java.util.Map)
     */
    @Override
    @Transactional
    public void addContentItem(Snapshot snapshot,
                               String contentId,
                               Map<String, String> props)
        throws SnapshotException {
        
        String contentIdHash = getIdChecksum(contentId);
        try{
            
            if(this.snapshotContentItemRepo.findBySnapshotAndContentIdHash(snapshot, contentIdHash) != null){
                return;
            }
            
            SnapshotContentItem item = new SnapshotContentItem();
            item.setContentId(contentId);
            item.setSnapshot(snapshot);
            item.setContentIdHash(contentIdHash);
            String propString = PropertiesSerializer.serialize(props);
            item.setMetadata(propString);
            this.snapshotContentItemRepo.save(item);
        } catch(Exception ex){
            throw new SnapshotException("failed to add content item: " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional
    public void addAlternateSnapshotIds(Snapshot snapshot, List<String> alternateIds) throws AlternateIdAlreadyExistsException {
        
        for(String altId : alternateIds){
            Snapshot altSnapshot = this.snapshotRepo.findBySnapshotAlternateIds(altId);
            if (altSnapshot != null && !altSnapshot.getName().equals(snapshot.getName())) {
                throw new AlternateIdAlreadyExistsException("The alternate snapshot id ("
                    + altId + ") already exists in another snapshot (" + altSnapshot.getName()+")");
            }
        }
        snapshot.addSnapshotAlternateIds(alternateIds);
        this.snapshotRepo.save(snapshot);
    }

    // Allows use of the non-thread-safe ChecksumUtil in a threaded environment
    private synchronized String getIdChecksum(String contentId) {
        return checksumUtil.generateChecksum(contentId);
    }

    /* (non-Javadoc)
     * @see org.duracloud.snapshot.service.SnapshotManager#transferToDpnNodeComplete(org.duracloud.snapshot.db.model.Snapshot)
     */
    @Override
    @Transactional
    public Snapshot transferToDpnNodeComplete(String snapshotId)
        throws SnapshotException {
        try {
            Snapshot snapshot = getSnapshot(snapshotId);

            snapshot.setStatus(SnapshotStatus.CLEANING_UP);
            snapshot = this.snapshotRepo.saveAndFlush(snapshot);

            File snapshotDir =
                new File(ContentDirUtils.getDestinationPath(snapshot.getName(),
                                                            bridgeConfig.getContentRootDir()));

             File zipFile = zipMetadata(snapshotId, snapshotDir);
            
            DuracloudEndPointConfig source = snapshot.getSource();

            ContentStore store = getContentStore(source);
            
            String zipChecksum = this.checksumUtil.generateChecksum(zipFile);            
            store.addContent(Constants.SNAPSHOT_METADATA_SPACE,
                             zipFile.getName(),
                             new FileInputStream(zipFile),
                             zipFile.length(),
                             "application/zip",
                             zipChecksum,
                             null);
            
            zipFile.delete();
            
            FileUtils.deleteDirectory(snapshotDir);

            String spaceId = source.getSpaceId();
            // Call DuraCloud to clean up snapshot
            getSnapshotTaskClient(source).cleanupSnapshot(spaceId);
            log.info("successfully initiated snapshot cleanup on DuraCloud for snapshotId = "
                + snapshotId + "; spaceId = " + spaceId);
            
            return snapshot;
        } catch (Exception e) {
            String message = "failed to initiate snapshot clean up: " + e.getMessage();
            log.error(message, e);
            throw new SnapshotManagerException(e.getMessage());
        }
    }

    /**
     * @param snapshotId
     * @param snapshotDir
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private File zipMetadata(String snapshotId, File snapshotDir)
        throws FileNotFoundException,
            IOException {

        File zipFile = new File(snapshotDir, snapshotId+".zip");
        
        FileOutputStream fileOutputStream = new FileOutputStream(zipFile);
        ZipOutputStream zipOs = new ZipOutputStream(fileOutputStream);
        
        for(String file : METADATA_FILENAMES) {
            IOUtil.addFileToZipOutputStream(new File(snapshotDir, file), zipOs);
        }

        zipOs.close();
        return zipFile;
    }

    /**
     * @param source
     * @return
     */
    private ContentStore getContentStore(DuracloudEndPointConfig source) {
        ContentStore store =
            storeClientHelper.create(source,
                                     bridgeConfig.getDuracloudUsername(),
                                     bridgeConfig.getDuracloudPassword());
        return store;
    }


    /**
     * Build the snapshot task client - for communicating with the DuraCloud snapshot
     * provider to perform tasks.
     *
     * @param source DuraCloud connection source
     * @return task client
     */
    private SnapshotTaskClient getSnapshotTaskClient(DuracloudEndPointConfig source) {
        return this.snapshotTaskClientHelper.create(source,
                                                    bridgeConfig.getDuracloudUsername(),
                                                    bridgeConfig.getDuracloudPassword());
    }

    /**
     * @param snapshotId
     * @return
     */
    private Snapshot getSnapshot(String snapshotId) throws SnapshotException {
        Snapshot snapshot = this.snapshotRepo.findByName(snapshotId);
        if (snapshot == null) {
            throw new SnapshotNotFoundException("A snapshot with id "
                + snapshotId + " does not exist.");
        }
        return snapshot;
    }

    private Snapshot cleanupComplete(Snapshot snapshot)
        throws SnapshotException {
        snapshot.setEndDate(new Date());
        snapshot.setStatus(SnapshotStatus.SNAPSHOT_COMPLETE);
        snapshot.setStatusText("");
        snapshot = snapshotRepo.saveAndFlush(snapshot);
        String snapshotId = snapshot.getName();
        String message = "Snapshot complete: " + snapshotId;
        List<String> recipients =
            new ArrayList<>(Arrays.asList(this.bridgeConfig.getDuracloudEmailAddresses()));
        String userEmail = snapshot.getUserEmail();
        if (userEmail != null) {
            recipients.add(userEmail);
        }

        if (recipients.size() > 0) {
            this.notificationManager.sendNotification(NotificationType.EMAIL,
                                                      message,
                                                      message,
                                                      recipients.toArray(new String[0]));
        }
        
        return snapshot;
    }
    
    /* (non-Javadoc)
     * @see org.duracloud.snapshot.service.SnapshotManager#finalizeSnapshots()
     */
    @Override
    @Transactional
    public void finalizeSnapshots() {
         log.debug("Running finalize snapshots...");
        List<Snapshot> snapshots =
            this.snapshotRepo.findByStatus(SnapshotStatus.CLEANING_UP);
        for(Snapshot snapshot : snapshots){
            DuracloudEndPointConfig source = snapshot.getSource();
            ContentStore store = getContentStore(source);
            try {
                String spaceId = source.getSpaceId();
                Iterator<String> it  = store.getSpaceContents(spaceId);
                if(!it.hasNext()) {
                    // Call DuraCloud to complete snapshot
                    log.debug("notifying task provider that snapshot " +
                              "is complete for space " + spaceId );
                    CompleteSnapshotTaskResult result =
                        getSnapshotTaskClient(source).completeSnapshot(spaceId);
                    log.info("snapshot complete call to task provider performed " +
                             "for space " + spaceId + ": result = " + result.getResult());

                    //update snapshot status and notify users
                    cleanupComplete(snapshot);
                }
            } catch (Exception e) {
                log.error("failed to cleanup " + source);
            }
        }
    }

    /* (non-Javadoc)
     * @see org.duracloud.snapshot.service.SnapshotManager#updateHistory()
     */
    @Override
    @Transactional
    public Snapshot updateHistory(Snapshot snapshot, String history) {
        SnapshotHistory newHistory = new SnapshotHistory();
        newHistory.setHistory(history);
        newHistory.setSnapshot(snapshot);
        snapshot.getSnapshotHistory().add(newHistory);
        return this.snapshotRepo.save(snapshot);
    }

    /**
     * @param storeClientHelper the storeClientHelper to set
     */
    public void setStoreClientHelper(StoreClientHelper storeClientHelper) {
        this.storeClientHelper = storeClientHelper;
    }

}
