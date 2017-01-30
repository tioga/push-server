/*
 * Copyright (c) 2014 Jacob D. Parr
 *
 * This software may not be used without permission.
 */
package org.tiogasolutions.push.engine.resources;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.tiogasolutions.dev.common.EqualsUtils;
import org.tiogasolutions.dev.common.exceptions.ApiException;
import org.tiogasolutions.push.engine.resources.api.ApiResourceV3;
import org.tiogasolutions.push.engine.resources.manage.ManageResource;
import org.tiogasolutions.push.engine.system.PubUtils;
import org.tiogasolutions.push.engine.view.Thymeleaf;
import org.tiogasolutions.push.engine.view.ThymeleafViewFactory;
import org.tiogasolutions.push.kernel.accounts.Account;
import org.tiogasolutions.push.kernel.accounts.AccountStore;
import org.tiogasolutions.push.kernel.accounts.DomainStore;
import org.tiogasolutions.push.kernel.clients.DomainProfileEntity;
import org.tiogasolutions.push.kernel.execution.ExecutionManager;
import org.tiogasolutions.push.kernel.requests.PushRequest;
import org.tiogasolutions.push.kernel.requests.PushRequestStore;
import org.tiogasolutions.push.kernel.system.PluginManager;
import org.tiogasolutions.push.kernel.system.Session;
import org.tiogasolutions.push.kernel.system.SessionStore;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import static org.tiogasolutions.push.kernel.Paths.*;

@Path("/")
@Scope(value = "prototype")
public class RootResource extends RootResourceSupport {

    public static final int REASON_CODE_INVALID_USERNAME_OR_PASSWORD = -1;
    public static final int REASON_CODE_UNAUTHORIZED = -2;
    public static final int REASON_SIGNED_OUT = -3;

    private static final Log log = LogFactory.getLog(RootResource.class);

    @Context
    private UriInfo uriInfo;

    @Autowired
    private ExecutionManager executionManager;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private AccountStore accountStore;

    @Autowired
    private DomainStore domainStore;

    @Autowired
    private PushRequestStore pushRequestStore;

    public RootResource() {
        log.info("Created ");
    }

    public PubUtils newPubUtils() {
        return new PubUtils(uriInfo);
    }

    @Override
    public UriInfo getUriInfo() {
        return uriInfo;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Thymeleaf getWelcome(@QueryParam("r") int reasonCode, @QueryParam("username") String username, @QueryParam("password") String password) throws IOException {

        String message = "";
        if (REASON_CODE_INVALID_USERNAME_OR_PASSWORD == reasonCode) {
            message = "Invalid username or password";
        } else if (REASON_CODE_UNAUTHORIZED == reasonCode) {
            message = "Your session has expired";
        } else if (REASON_SIGNED_OUT == reasonCode) {
            message = "You have successfully signed out";
        }

        return new Thymeleaf(executionManager.getContext().getSession(), ThymeleafViewFactory.WELCOME, new WelcomeModel(executionManager.getContext().getAccount(), message, username, password));
    }

    public static class WelcomeModel {
        private final Account account;
        private final String message;
        private final String emailAddress;
        private final String password;

        public WelcomeModel(Account account, String message, String emailAddress, String password) {
            this.account = account;
            this.message = message;
            this.emailAddress = emailAddress;
            this.password = password;
        }

        public Account getAccount() {
            return account;
        }

        public String getMessage() {
            return message;
        }

        public String getEmailAddress() {
            return emailAddress;
        }

        public String getPassword() {
            return password;
        }
    }

    @POST
    @Path($signIn)
    @Produces(MediaType.TEXT_HTML)
    public Response signIn(@FormParam("username") String username, @FormParam("password") String password, @CookieParam(SessionStore.SESSION_COOKIE_NAME) String sessionId) throws Exception {

        Account account = accountStore.getByEmail(username);

        if (account == null || EqualsUtils.objectsNotEqual(account.getPassword(), password)) {
            sessionStore.remove(sessionId);

            NewCookie sessionCookie = SessionStore.toCookie(getUriInfo(), null);
            URI other = getUriInfo().getBaseUriBuilder().queryParam("r", REASON_CODE_INVALID_USERNAME_OR_PASSWORD).build();
            return Response.seeOther(other).cookie(sessionCookie).build();
        }

        Session session = sessionStore.newSession(username);

        NewCookie sessionCookie = SessionStore.toCookie(getUriInfo(), session);
        URI other = getUriInfo().getBaseUriBuilder().path("manage").build();
        return Response.seeOther(other).cookie(sessionCookie).build();
    }

    @GET
    @Path($signOut)
    @Produces(MediaType.TEXT_HTML)
    public Response signOut(@CookieParam(SessionStore.SESSION_COOKIE_NAME) String sessionId) throws Exception {
        if (sessionId != null) {
            sessionStore.remove(sessionId);
        }
        NewCookie sessionCookie = SessionStore.toCookie(getUriInfo(), null);
        URI other = getUriInfo().getBaseUriBuilder().queryParam("r", REASON_SIGNED_OUT).build();
        return Response.seeOther(other).cookie(sessionCookie).build();
    }

    @Path($api_v3)
    public ApiResourceV3 getApiResourceV2() throws Exception {
        return new ApiResourceV3(executionManager, newPubUtils());
    }

    @Path($manage)
    public ManageResource getManageResource() {
        return new ManageResource(executionManager, domainStore, accountStore, pushRequestStore, sessionStore, pluginManager, uriInfo);
    }

    @GET
    @Path("/q/{pushRequestId}")
    public Response resolveCallback(@PathParam("pushRequestId") String pushRequestId) throws URISyntaxException {

        PushRequest request = pushRequestStore.getByPushRequestId(pushRequestId);
        if (request == null) {
            throw ApiException.notFound("API request not found for " + pushRequestId);
        }

        DomainProfileEntity domain = domainStore.getByDocumentId(request.getDomainId());
        if (domain == null) {
            throw ApiException.notFound("Domain not found for " + request.getDomainId());
        }

        return Response.seeOther(getUriInfo().getBaseUriBuilder().build()).build();
    }

    @GET
    @Path($healthCheck)
    @Produces(MediaType.TEXT_HTML)
    public Response healthCheck$GET() {
        return Response.status(Response.Status.OK).build();
    }
}

