/*
  Copyright 2012 - 2015 pac4j organization

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.pac4j.core.client;

import java.util.ArrayList;
import java.util.List;

import org.pac4j.core.authorization.AuthorizationGenerator;
import org.pac4j.core.client.RedirectAction.RedirectType;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.Authenticator;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileCreator;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.core.util.InitializableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This class is the default implementation of an authentication client (whatever the protocol). It has the core concepts :</p>
 * <ul>
 * <li>the initialization process is handled by the {@link InitializableObject} inheritance, the {@link #internalInit()} must be implemented
 * in sub-classes</li>
 * <li>the cloning process is handled by the {@link #clone()} method, the {@link #newClient()} method must be implemented in sub-classes to
 * create a new instance</li>
 * <li>the callback url is handled through the {@link #setCallbackUrl(String)} and {@link #getCallbackUrl()} methods</li>
 * <li>the name of the client is handled through the {@link #setName(String)} and {@link #getName()} methods</li>
 * <li>the concept of "direct" redirection is defined through the {@link #isDirectRedirection()} method : if true, the
 * {@link #redirect(WebContext, boolean, boolean)} method will always return the redirection to the provider where as if it's false, the
 * redirection url will be the callback url with an additionnal parameter : {@link #NEEDS_CLIENT_REDIRECTION_PARAMETER} to require the
 * redirection, which will be handled <b>later</b> in the {@link #getCredentials(WebContext)} method.
 * To force a direct redirection, the {@link #getRedirectAction(WebContext, boolean, boolean)} must be used with <code>true</code> for the
 * <code>forceDirectRedirection</code> parameter</li>
 * <li>if you enable "contextual redirects" by using the {@link #setEnableContextualRedirects(boolean)}, you can use relative callback urls
 * which will be completed according to the current host, port and scheme. Disabled by default.</li>
 * </ul>
 * <p>The {@link #authenticator} and {@link #profileCreator} can be used for some clients where the protocol (like HTTP) does not provide
 * any defined implementation to validate credentials and create user profiles.</p>
 * <p>The {@link #init()} method must be called implicitly by the main methods of the {@link Client} interface, so that no explicit call is
 * required to initialize the client.</p>
 * <p>The {@link #getMechanism()} method returns the implemented {@link Mechanism} by the client.</p>
 * <p>After retrieving the user profile, the client can generate the authorization information (roles, permissions and remember-me) by using
 * the appropriate {@link AuthorizationGenerator}, which is by default <code>null</code>.</p>
 * 
 * @author Jerome Leleu
 * @since 1.4.0
 */
public abstract class BaseClient<C extends Credentials, U extends CommonProfile> extends InitializableObject implements
        Client<C, U>, Cloneable {

    protected static final Logger logger = LoggerFactory.getLogger(BaseClient.class);

    public final static String NEEDS_CLIENT_REDIRECTION_PARAMETER = "needs_client_redirection";

    public final static String ATTEMPTED_AUTHENTICATION_SUFFIX = "$attemptedAuthentication";

    protected String callbackUrl;

    private String name;

    private boolean enableContextualRedirects = false;
    
    private boolean includeClientNameInCallbackUrl = true;

    private List<AuthorizationGenerator<U>> authorizationGenerators = new ArrayList<AuthorizationGenerator<U>>();

    private Authenticator<C> authenticator;

    private ProfileCreator<C, U> profileCreator;

    /**
     * Clone the current client.
     * 
     * @return the cloned client
     */
    @Override
    public BaseClient<C, U> clone() {
        final BaseClient<C, U> newClient = newClient();
        newClient.setCallbackUrl(this.callbackUrl);
        newClient.setName(this.name);
        newClient.setAuthenticator(this.authenticator);
        newClient.setProfileCreator(this.profileCreator);
        return newClient;
    }

    /**
     * Create a new instance of the client.
     * 
     * @return A new instance of the client
     */
    protected abstract BaseClient<C, U> newClient();

    public void setCallbackUrl(final String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getCallbackUrl() {
        return this.callbackUrl;
    }

    public String getContextualCallbackUrl(final WebContext context) {
        return prependHostToUrlIfNotPresent(this.callbackUrl, context);
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        if (CommonHelper.isBlank(this.name)) {
            return this.getClass().getSimpleName();
        }
        return this.name;
    }

    /**
     * Define if this client has a direct redirection.
     * 
     * @return if this client has a direct redirection
     */
    protected abstract boolean isDirectRedirection();

    @Override
    public final void redirect(final WebContext context, final boolean requiresAuthentication, final boolean ajaxRequest)
            throws RequiresHttpAction {
        final RedirectAction action = getRedirectAction(context, requiresAuthentication, ajaxRequest);
        if (action.getType() == RedirectType.REDIRECT) {
            context.setResponseStatus(HttpConstants.TEMP_REDIRECT);
            context.setResponseHeader(HttpConstants.LOCATION_HEADER, action.getLocation());
        } else if (action.getType() == RedirectType.SUCCESS) {
            context.setResponseStatus(HttpConstants.OK);
            context.writeResponseContent(action.getContent());
        }
    }

    /**
     * Get the redirectAction computed for this client. All the logic is encapsulated here. It should not be called be directly, the
     * {@link #redirect(WebContext, boolean, boolean)} should be generally called instead.
     * 
     * @param context context
     * @param requiresAuthentication requires authentication
     * @return the redirection action
     * @throws RequiresHttpAction requires an additional HTTP action
     */
    public final RedirectAction getRedirectAction(final WebContext context, final boolean requiresAuthentication,
            final boolean ajaxRequest) throws RequiresHttpAction {
        init();
        // it's an AJAX request -> unauthorized (instead of a redirection)
        if (ajaxRequest) {
            throw RequiresHttpAction.unauthorized("AJAX request -> 401", context, null);
        }
        // authentication has already been tried
        final String attemptedAuth = (String) context.getSessionAttribute(getName() + ATTEMPTED_AUTHENTICATION_SUFFIX);
        if (CommonHelper.isNotBlank(attemptedAuth)) {
            context.setSessionAttribute(getName() + ATTEMPTED_AUTHENTICATION_SUFFIX, null);
            // protected target -> forbidden
            if (requiresAuthentication) {
                logger.error("authentication already tried and protected target -> forbidden");
                throw RequiresHttpAction.forbidden("authentication already tried -> forbidden", context);
            }
        }
        // it's a direct redirection or force the redirection -> return the real redirection
        if (isDirectRedirection() || requiresAuthentication) {
            return retrieveRedirectAction(context);
        } else {
            // return an intermediate url which is the callback url with a specific parameter requiring redirection
            final String intermediateUrl = CommonHelper.addParameter(getContextualCallbackUrl(context),
                    NEEDS_CLIENT_REDIRECTION_PARAMETER, "true");
            return RedirectAction.redirect(intermediateUrl);
        }
    }

    /**
     * Return the redirection url to the provider, requested from an anonymous page.
     *
     * @param context the current web context
     * @return the redirection url to the provider.
     */
    public String getRedirectionUrl(final WebContext context) {
        try {
            return getRedirectAction(context, false, false).getLocation();
        } catch (final RequiresHttpAction e) {
            return null;
        }
    }

    protected abstract RedirectAction retrieveRedirectAction(final WebContext context);

    @Override
    public final C getCredentials(final WebContext context) throws RequiresHttpAction {
        init();
        final String value = context.getRequestParameter(NEEDS_CLIENT_REDIRECTION_PARAMETER);
        // needs redirection -> return the redirection url
        if (CommonHelper.isNotBlank(value)) {
            final RedirectAction action = retrieveRedirectAction(context);
            final String message = "Needs client redirection";
            if (action.getType() == RedirectType.SUCCESS) {
                throw RequiresHttpAction.ok(message, context, action.getContent());
            } else {
                // it's a redirect
                throw RequiresHttpAction.redirect(message, context, action.getLocation());
            }
        } else {
            // else get the credentials
            final C credentials = retrieveCredentials(context);
            // no credentials -> save this authentication has already been tried and failed
            if (credentials == null) {
                context.setSessionAttribute(getName() + ATTEMPTED_AUTHENTICATION_SUFFIX, "true");
            } else {
                context.setSessionAttribute(getName() + ATTEMPTED_AUTHENTICATION_SUFFIX, null);
            }
            return credentials;
        }
    }

    protected abstract C retrieveCredentials(final WebContext context) throws RequiresHttpAction;

    @Override
    public final U getUserProfile(final C credentials, final WebContext context) {
        init();
        logger.debug("credentials : {}", credentials);
        if (credentials == null) {
            return null;
        }

        final U profile = retrieveUserProfile(credentials, context);
        if (this.authorizationGenerators != null) {
            for (AuthorizationGenerator<U> authorizationGenerator : this.authorizationGenerators) {
                authorizationGenerator.generate(profile);
            }
        }
        return profile;
    }

    protected abstract U retrieveUserProfile(final C credentials, final WebContext context);

    /**
     * Return the implemented mechanism.
     * 
     * @return the implemented mechanism
     */
    public abstract Mechanism getMechanism();

    @Override
    public String toString() {
        return CommonHelper.toString(this.getClass(), "callbackUrl", this.callbackUrl, "name", this.name,
                "isDirectRedirection", isDirectRedirection(), "enableContextualRedirects",
                isEnableContextualRedirects());
    }

    /**
     * Returns if contextual redirects are enabled for this client
     * 
     * @return if contextual redirects are enabled for this client
     */
    public boolean isEnableContextualRedirects() {
        return this.enableContextualRedirects;
    }

    /**
     * Sets whether contextual redirects are enabled for this client
     * 
     * @param enableContextualRedirects enable contextual redirects
     */
    public void setEnableContextualRedirects(final boolean enableContextualRedirects) {
        this.enableContextualRedirects = enableContextualRedirects;
    }

    /**
     * Returns if the client name should be implicitly added to the callback url if it is not already specified
     * 
     * @return if the client name should be implicitly added to the callback url if it is not already specified
     */
    public boolean isIncludeClientNameInCallbackUrl() {
    	return this.includeClientNameInCallbackUrl;
    }
    
    /**
     * Sets whether the client name should be implicitly added to the callback url for this client.
     * 
     * @param enableContextualRedirects enable inclusion of the client name in the callback url.
     */
    public void setIncludeClientNameInCallbackUrl(final boolean includeClientNameInCallbackUrl) {
    	this.includeClientNameInCallbackUrl = includeClientNameInCallbackUrl;
    }
    
    protected String prependHostToUrlIfNotPresent(final String url, final WebContext webContext) {
        if (webContext != null && this.enableContextualRedirects && url != null && !url.startsWith("http://")
                && !url.startsWith("https://")) {
            final StringBuilder sb = new StringBuilder();

            sb.append(webContext.getScheme()).append("://").append(webContext.getServerName());

            if (webContext.getServerPort() != HttpConstants.DEFAULT_PORT) {
                sb.append(":").append(webContext.getServerPort());
            }

            sb.append(url.startsWith("/") ? url : "/" + url);

            return sb.toString();
        }

        return url;
    }

    /**
     * Return the state parameter required by some security protocols like SAML or OAuth.
     * 
     * @param webContext web context
     * @return the state
     */
    protected String getStateParameter(WebContext webContext) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void addAuthorizationGenerator(AuthorizationGenerator<U> authorizationGenerator) {
        if (this.authorizationGenerators != null) {
            this.authorizationGenerators.add(authorizationGenerator);
        }
    }

    public List<AuthorizationGenerator<U>> getAuthorizationGenerators() {
        return this.authorizationGenerators;
    }

    public void setAuthorizationGenerators(List<AuthorizationGenerator<U>> authorizationGenerators) {
        this.authorizationGenerators = authorizationGenerators;
    }

    /**
     * Use addAuthorizationGenerator instead.
     * 
     * @param authorizationGenerator an authorizations generator
     */
    @Deprecated
    public void setAuthorizationGenerator(final AuthorizationGenerator<U> authorizationGenerator) {
        addAuthorizationGenerator(authorizationGenerator);
    }

    public Authenticator<C> getAuthenticator() {
        return this.authenticator;
    }

    public void setAuthenticator(Authenticator<C> authenticator) {
        this.authenticator = authenticator;
    }

    public ProfileCreator<C, U> getProfileCreator() {
        return this.profileCreator;
    }

    public void setProfileCreator(ProfileCreator<C, U> profileCreator) {
        this.profileCreator = profileCreator;
    }
}
