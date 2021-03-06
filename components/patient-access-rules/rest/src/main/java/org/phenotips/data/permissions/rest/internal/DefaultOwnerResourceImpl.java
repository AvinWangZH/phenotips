/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.data.permissions.rest.internal;

import org.phenotips.data.permissions.PatientAccess;
import org.phenotips.data.permissions.rest.DomainObjectFactory;
import org.phenotips.data.permissions.rest.OwnerResource;
import org.phenotips.data.permissions.rest.PermissionsResource;
import org.phenotips.data.permissions.rest.internal.utils.LinkBuilder;
import org.phenotips.data.permissions.rest.internal.utils.PatientAccessContext;
import org.phenotips.data.permissions.rest.internal.utils.RESTActionResolver;
import org.phenotips.data.permissions.rest.internal.utils.SecureContextFactory;
import org.phenotips.data.permissions.rest.model.Link;
import org.phenotips.data.permissions.rest.model.OwnerRepresentation;
import org.phenotips.data.rest.PatientResource;
import org.phenotips.data.rest.Relations;

import org.xwiki.component.annotation.Component;
import org.xwiki.container.Container;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.rest.XWikiResource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

/**
 * Default implementation for {@link OwnerResource} using XWiki's support for REST resources.
 *
 * @version $Id$
 * @since 1.3M2
 */
@Component
@Named("org.phenotips.data.permissions.rest.internal.DefaultOwnerResourceImpl")
@Singleton
public class DefaultOwnerResourceImpl extends XWikiResource implements OwnerResource
{
    @Inject
    private Logger logger;

    @Inject
    private SecureContextFactory secureContextFactory;

    @Inject
    @Named("userOrGroup")
    private DocumentReferenceResolver<String> userOrGroupResolver;

    @Inject
    private DomainObjectFactory factory;

    @Inject
    private Container container;

    @Inject
    private RESTActionResolver restActionResolver;

    @Override
    public OwnerRepresentation getOwner(String patientId)
    {
        this.logger.debug("Retrieving patient record's owner [{}] via REST", patientId);
        // besides getting the patient, checks that the user has view access
        PatientAccessContext patientAccessContext = this.secureContextFactory.getReadContext(patientId);

        OwnerRepresentation result = this.factory.createOwnerRepresentation(patientAccessContext.getPatient());

        // adding links relative to this context
        result.withLinks(new LinkBuilder(this.uriInfo, this.restActionResolver)
            .withAccessLevel(patientAccessContext.getPatientAccess().getAccessLevel())
            .withActionableResources(PermissionsResource.class)
            .withRootInterface(this.getClass().getInterfaces()[0])
            .build());
        result.getLinks().add(new Link().withRel(Relations.PATIENT_RECORD)
            .withHref(this.uriInfo.getBaseUriBuilder().path(PatientResource.class).build(patientId).toString()));

        return result;
    }

    @Override
    public Response setOwner(OwnerRepresentation owner, String patientId)
    {
        try {
            return putOwner(owner.getId(), patientId);
        } catch (Exception ex) {
            this.logger.error("The json was not properly formatted", ex.getMessage());
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
    }

    @Override
    public Response setOwner(String patientId)
    {
        String ownerId = (String) this.container.getRequest().getProperty("owner");
        if (StringUtils.isNotBlank(ownerId)) {
            return putOwner(ownerId, patientId);
        }
        this.logger.error("The owner id was not provided or is invalid");
        throw new WebApplicationException(Status.BAD_REQUEST);
    }

    private Response putOwner(String ownerId, String patientId)
    {
        if (StringUtils.isBlank(ownerId)) {
            this.logger.error("The owner id was not provided");
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
        this.logger.debug("Setting owner of the patient record [{}] to [{}] via REST", patientId, ownerId);
        // besides getting the patient, checks that the current user has manage access
        PatientAccessContext patientAccessContext = this.secureContextFactory.getWriteContext(patientId);

        EntityReference ownerReference = this.userOrGroupResolver.resolve(ownerId);
        if (ownerReference == null) {
            // what would be a better status to indicate that the user/group id is not valid?
            // ideally, the status page should show some sort of a message indicating that the id was not found
            throw new WebApplicationException(
                new IllegalArgumentException("Specified user/group was not found"), Status.NOT_FOUND);
        }
        // todo. ask Sergiu as to what the right thing to do is
        // the code in DefaultPatientAccessHelper needs to be changed
        // this is just a hack
        // the helper in PatientAccess needs to use this.entitySerializer.serialize
        DocumentReference ownerDocRef = new DocumentReference(ownerReference);

        PatientAccess patientAccess = patientAccessContext.getPatientAccess();
        if (!patientAccess.setOwner(ownerDocRef)) {
            // todo. should this status be an internal server error, or a bad request?
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }

        return Response.ok().build();
    }
}
