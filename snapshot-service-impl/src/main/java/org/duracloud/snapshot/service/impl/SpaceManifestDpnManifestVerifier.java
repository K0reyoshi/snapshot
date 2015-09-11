/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.snapshot.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

import org.duracloud.client.ContentStore;
import org.duracloud.common.collection.WriteOnlyStringSet;
import org.duracloud.common.constant.Constants;
import org.duracloud.common.constant.ManifestFormat;
import org.duracloud.manifest.ManifestFormatter;
import org.duracloud.manifest.impl.TsvManifestFormatter;
import org.duracloud.manifeststitch.StitchedManifestGenerator;
import org.duracloud.mill.db.model.ManifestItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class verifies that the space manifest and the DPN manifests match. It
 * will ignore the .collection-snapshot.properties file on the space manifest
 * side. Additionally, since the DPN manifest is a stitched view of what should
 * be in the space, the stitched, rather than the unstitched, view of the space
 * is used for comparison. and it will only compare
 * 
 * @author Daniel Bernstien
 */
public class SpaceManifestDpnManifestVerifier {

    private static final Logger log = LoggerFactory.getLogger(SpaceManifestDpnManifestVerifier.class);

    private File md5Manifest;
    private StitchedManifestGenerator generator;
    private String spaceId;
    private List<String> errors;

    public SpaceManifestDpnManifestVerifier(File md5Manifest, StitchedManifestGenerator generator, String spaceId) {
        this.md5Manifest = md5Manifest;
        this.generator = generator;
        this.spaceId = spaceId;
    }

    /**
     * Performs the verification.
     * @return true if verification was a success. Otherwise false. Errors can
     *         be obtained by calling getErrors() after execution completes.
     */
    public boolean verify() {
        if (this.errors != null) {
            return getResult(this.errors);
        }

        this.errors = new LinkedList<>();
        try {
            WriteOnlyStringSet dpnManifest = ManifestFileHelper.loadManifestSetFromFile(this.md5Manifest);

            BufferedReader reader =
                new BufferedReader(new InputStreamReader(generator.generate(spaceId, ManifestFormat.TSV)));
            ManifestFormatter formatter = new TsvManifestFormatter();
            // skip header
            if (formatter.getHeader() != null) {
                reader.readLine();
            }

            String line = null;
            int stitchedManifestCount = 0;
            while ((line = reader.readLine()) != null) {
                ManifestItem item = formatter.parseLine(line);
                String contentId = item.getContentId();
                if (!contentId.equals(Constants.SNAPSHOT_PROPS_FILENAME)) {
                    if (!dpnManifest.contains(ManifestFileHelper.formatManifestSetString(contentId,
                                                                                         item.getContentChecksum()))) {
                        errors.add("DPN manifest does not contain content id/checksum combination ("
                            + contentId + ", " + item.getContentChecksum());
                    }
                    stitchedManifestCount++;
                }
            }

            int dpnCount = dpnManifest.size();
            if (stitchedManifestCount != dpnCount) {
                errors.add("DPN Manifest size ("
                    + dpnCount + ") does not equal DuraCloud Manifest (" + stitchedManifestCount + ")");
            }

        } catch (Exception e) {
            String message = "Failed to verify space manifest against dpn manifest:" + e.getMessage();
            errors.add(message);
            log.error(message, e);
        }

        return getResult(errors);
    }

    private boolean getResult(List<String> errors) {
        return errors.size() == 0;
    }

    public List<String> getErrors() {
        if (this.errors == null) {
            throw new IllegalStateException("You must call execute() before attempting to access the errors");
        } else {
            return this.errors;
        }
    }

}