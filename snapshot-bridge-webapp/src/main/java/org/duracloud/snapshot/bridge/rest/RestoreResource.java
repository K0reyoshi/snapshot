/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.snapshot.bridge.rest;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.httpclient.HttpStatus;
import org.duracloud.snapshot.db.model.DuracloudEndPointConfig;
import org.duracloud.snapshot.db.model.Restoration;
import org.duracloud.snapshot.dto.bridge.CompleteRestoreBridgeResult;
import org.duracloud.snapshot.dto.bridge.CreateRestoreBridgeParameters;
import org.duracloud.snapshot.dto.bridge.CreateRestoreBridgeResult;
import org.duracloud.snapshot.dto.bridge.GetRestoreStatusBridgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Defines the REST resource layer for interacting with the Snapshot processing
 * engine.
 * 
 * @author Daniel Bernstein Date: Feb 4, 2014
 */
@Component
@Path("/restore")
public class RestoreResource {

    private static Logger log =
        LoggerFactory.getLogger(RestoreResource.class);

    @Context
    HttpServletRequest request;

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uriInfo;

    private RestoreManager restorationManager;

    @Autowired
    public RestoreResource(RestoreManager restorationManager) {
        this.restorationManager = restorationManager;
    }

    @Path("/")
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response restoreSnapshot(CreateRestoreBridgeParameters  params) {
        try {
            DuracloudEndPointConfig destination = new DuracloudEndPointConfig();
            destination.setHost(params.getHost());
            destination.setPort(Integer.valueOf(params.getPort()));
            destination.setStoreId(params.getStoreId());
            destination.setSpaceId(params.getSpaceId());
            Restoration result =
                this.restorationManager.restoreSnapshot(params.getSnapshotId(),
                                                        destination, params.getUserEmail());
            return Response.ok()
                           .entity(new CreateRestoreBridgeResult(result.getId(),
                                                                 result.getStatus()))
                           .build();
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            return Response.serverError()
                           .entity(new ResponseDetails(ex.getMessage()))
                           .build();
        }
    }

    @Path("{restorationId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    /**
     * Returns the status of a snapshot. The fields available in the response will match
     * those in <code>SnapshotStatus</code>.
     * @param snapshotId
     * @return
     */
    public Response getStatus(@PathParam("restorationId") Long restorationId) {
        try {
            Restoration restoration =
                this.restorationManager.get(restorationId);

            return Response.ok()
                           .entity(new GetRestoreStatusBridgeResult(restoration.getStatus(),
                                                                    restoration.getStatusText()))
                           .build();
        } catch (RestorationNotFoundException ex) {
            log.error(ex.getMessage(), ex);
            return Response.status(HttpStatus.SC_NOT_FOUND)
                           .entity(new ResponseDetails(ex.getMessage()))
                           .build();
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            return Response.serverError()
                           .entity(new ResponseDetails(ex.getMessage()))
                           .build();
        }
    }

    @Path("{restorationId}/complete")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response
        restoreComplete(@PathParam("restorationId") Long restorationId) {

        try {
            Restoration restoration =
                this.restorationManager.restorationCompleted(restorationId);
            return Response.ok()
                           .entity(new CompleteRestoreBridgeResult(restoration.getStatus(),
                                                                   restoration.getStatusText()))
                           .build();
        } catch (RestorationNotFoundException ex) {
            log.error(ex.getMessage(), ex);
            return Response.status(HttpStatus.SC_NOT_FOUND)
                           .entity(new ResponseDetails(ex.getMessage()))
                           .build();
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            return Response.serverError()
                           .entity(new ResponseDetails(ex.getMessage()))
                           .build();
        }
    }

}
