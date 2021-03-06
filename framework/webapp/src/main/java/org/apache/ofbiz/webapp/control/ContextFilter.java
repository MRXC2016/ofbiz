/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.webapp.control;

import static org.apache.ofbiz.base.util.UtilGenerics.checkMap;

import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.StringUtil;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilObject;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtil;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.webapp.WebAppUtil;
import org.apache.ofbiz.webapp.website.WebSiteWorker;

/**
 * ContextFilter - Restricts access to raw files and configures servlet objects.
 */
public class ContextFilter implements Filter {

    public static final String module = ContextFilter.class.getName();
    public static final String FORWARDED_FROM_SERVLET = "_FORWARDED_FROM_SERVLET_";

    protected FilterConfig config = null;
    protected boolean debug = false;

    // default charset used to decode requests body data if no encoding is specified in the request
    private String defaultCharacterEncoding;

    /**
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    public void init(FilterConfig config) throws ServletException {
        this.config = config;

        // puts all init-parameters in ServletContext attributes for easier parametrization without code changes
        this.putAllInitParametersInAttributes();

        // set debug
        this.debug = "true".equalsIgnoreCase(config.getInitParameter("debug"));
        if (!debug) {
            debug = Debug.verboseOn();
        }

        defaultCharacterEncoding = config.getServletContext().getInitParameter("charset");
        if (UtilValidate.isEmpty(defaultCharacterEncoding)) {
            defaultCharacterEncoding = "UTF-8";
        }
        // check the serverId
        getServerId();
        // initialize the delegator
        WebAppUtil.getDelegator(config.getServletContext());
        // initialize security
        WebAppUtil.getSecurity(config.getServletContext());
        // initialize the services dispatcher
        WebAppUtil.getDispatcher(config.getServletContext());

        // this will speed up the initial sessionId generation
        new java.security.SecureRandom().nextLong();
    }

    /**
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;


        // ----- Servlet Object Setup -----

        // set the ServletContext in the request for future use
        httpRequest.setAttribute("servletContext", config.getServletContext());

        // set the webSiteId in the session
        if (UtilValidate.isEmpty(httpRequest.getSession().getAttribute("webSiteId"))){
            httpRequest.getSession().setAttribute("webSiteId", WebSiteWorker.getWebSiteId(httpRequest));
        }

        // set the filesystem path of context root.
        httpRequest.setAttribute("_CONTEXT_ROOT_", config.getServletContext().getRealPath("/"));

        // set the server root url
        httpRequest.setAttribute("_SERVER_ROOT_URL_", UtilHttp.getServerRootUrl(httpRequest));

        // request attributes from redirect call
        String reqAttrMapHex = (String) httpRequest.getSession().getAttribute("_REQ_ATTR_MAP_");
        if (UtilValidate.isNotEmpty(reqAttrMapHex)) {
            byte[] reqAttrMapBytes = StringUtil.fromHexString(reqAttrMapHex);
            Map<String, Object> reqAttrMap = checkMap(UtilObject.getObject(reqAttrMapBytes), String.class, Object.class);
            if (reqAttrMap != null) {
                for (Map.Entry<String, Object> entry: reqAttrMap.entrySet()) {
                    httpRequest.setAttribute(entry.getKey(), entry.getValue());
                }
            }
            httpRequest.getSession().removeAttribute("_REQ_ATTR_MAP_");
        }

        // ----- Context Security -----
        // check if we are disabled
        String disableSecurity = config.getInitParameter("disableContextSecurity");
        if (disableSecurity != null && "Y".equalsIgnoreCase(disableSecurity)) {
            chain.doFilter(httpRequest, httpResponse);
            return;
        }

        // check if we are told to redirect everthing
        String redirectAllTo = config.getInitParameter("forceRedirectAll");
        if (UtilValidate.isNotEmpty(redirectAllTo)) {
            // little trick here so we don't loop on ourself
            if (httpRequest.getSession().getAttribute("_FORCE_REDIRECT_") == null) {
                httpRequest.getSession().setAttribute("_FORCE_REDIRECT_", "true");
                Debug.logWarning("Redirecting user to: " + redirectAllTo, module);

                if (!redirectAllTo.toLowerCase().startsWith("http")) {
                    redirectAllTo = httpRequest.getContextPath() + redirectAllTo;
                }
                httpResponse.sendRedirect(redirectAllTo);
                return;
            } else {
                httpRequest.getSession().removeAttribute("_FORCE_REDIRECT_");
                chain.doFilter(httpRequest, httpResponse);
                return;
            }
        }

        // test to see if we have come through the control servlet already, if not do the processing
        String requestPath = null;
        String contextUri = null;
        if (httpRequest.getAttribute(ContextFilter.FORWARDED_FROM_SERVLET) == null) {
            // Debug.logInfo("In ContextFilter.doFilter, FORWARDED_FROM_SERVLET is NOT set", module);
            String allowedPath = config.getInitParameter("allowedPaths");
            String redirectPath = config.getInitParameter("redirectPath");
            String errorCode = config.getInitParameter("errorCode");

            List<String> allowList = null;
            if ((allowList = StringUtil.split(allowedPath, ":")) != null) {
                allowList.add("/");  // No path is allowed.
                allowList.add("");   // No path is allowed.
            }

            if (debug) Debug.logInfo("[Domain]: " + httpRequest.getServerName() + " [Request]: " + httpRequest.getRequestURI(), module);

            requestPath = httpRequest.getServletPath();
            if (requestPath == null) requestPath = "";
            if (requestPath.lastIndexOf("/") > 0) {
                if (requestPath.indexOf("/") == 0) {
                    requestPath = "/" + requestPath.substring(1, requestPath.indexOf("/", 1));
                } else {
                    requestPath = requestPath.substring(1, requestPath.indexOf("/"));
                }
            }

            String requestInfo = httpRequest.getServletPath();
            if (requestInfo == null) requestInfo = "";
            if (requestInfo.lastIndexOf("/") >= 0) {
                requestInfo = requestInfo.substring(0, requestInfo.lastIndexOf("/")) + "/*";
            }

            StringBuilder contextUriBuffer = new StringBuilder();
            if (httpRequest.getContextPath() != null) {
                contextUriBuffer.append(httpRequest.getContextPath());
            }
            if (httpRequest.getServletPath() != null) {
                contextUriBuffer.append(httpRequest.getServletPath());
            }
            if (httpRequest.getPathInfo() != null) {
                contextUriBuffer.append(httpRequest.getPathInfo());
            }
            contextUri = contextUriBuffer.toString();

            // Verbose Debugging
            if (Debug.verboseOn()) {
                if (allowList != null) {
                    for (String allow: allowList) {
                        Debug.logVerbose("[Allow]: " + allow, module);
                    }
                }
                Debug.logVerbose("[Request path]: " + requestPath, module);
                Debug.logVerbose("[Request info]: " + requestInfo, module);
                Debug.logVerbose("[Servlet path]: " + httpRequest.getServletPath(), module);
            }

            // check to make sure the requested url is allowed
            if (allowList != null &&
                (!allowList.contains(requestPath) && !allowList.contains(requestInfo) && !allowList.contains(httpRequest.getServletPath()))
                ) {
                String filterMessage = "[Filtered request]: " + contextUri;
                
                if (redirectPath == null) {
                    int error = 404;
                    if (UtilValidate.isNotEmpty(errorCode)) {
                        try {
                            error = Integer.parseInt(errorCode);
                        } catch (NumberFormatException nfe) {
                            Debug.logWarning(nfe, "Error code specified would not parse to Integer : " + errorCode, module);
                        }
                    }
                    filterMessage = filterMessage + " (" + error + ")";
                    httpResponse.sendError(error, contextUri);
                    request.setAttribute("filterRequestUriError", contextUri);
                } else {
                    filterMessage = filterMessage + " (" + redirectPath + ")";
                    if (!redirectPath.toLowerCase().startsWith("http")) {
                        redirectPath = httpRequest.getContextPath() + redirectPath;
                    }
                    httpResponse.sendRedirect(redirectPath);
                }
                Debug.logWarning(filterMessage, module);
                return;
            }
        }

        if (request.getCharacterEncoding() == null) {
            request.setCharacterEncoding(defaultCharacterEncoding);
        }
        WebAppUtil.setAttributesFromRequestBody(request);

        // check if multi tenant is enabled
        boolean useMultitenant = EntityUtil.isMultiTenantEnabled();
        if (useMultitenant) {
            // get tenant delegator by domain name
            String serverName = httpRequest.getServerName();
            try {
                // if tenant was specified, replace delegator with the new per-tenant delegator and set tenantId to session attribute
                Delegator delegator = WebAppUtil.getDelegator(config.getServletContext());

                //Use base delegator for fetching data from entity of entityGroup org.apache.ofbiz.tenant
                Delegator baseDelegator = DelegatorFactory.getDelegator(delegator.getDelegatorBaseName());
                GenericValue tenantDomainName = EntityQuery.use(baseDelegator).from("TenantDomainName").where("domainName", serverName).queryOne();
                String tenantId = null;
                if(UtilValidate.isNotEmpty(tenantDomainName)) {
                    tenantId = tenantDomainName.getString("tenantId");
                }
                
                if(UtilValidate.isEmpty(tenantId)) {
                    tenantId = (String) httpRequest.getAttribute("userTenantId");
                }
                if(UtilValidate.isEmpty(tenantId)) {
                    tenantId = (String) httpRequest.getParameter("userTenantId");
                }
                if (UtilValidate.isNotEmpty(tenantId)) {
                    // if the request path is a root mount then redirect to the initial path
                    if (UtilValidate.isNotEmpty(requestPath) && requestPath.equals(contextUri)) {
                        GenericValue tenant = EntityQuery.use(baseDelegator).from("Tenant").where("tenantId", tenantId).queryOne();
                        String initialPath = tenant.getString("initialPath");
                        if (UtilValidate.isNotEmpty(initialPath) && !"/".equals(initialPath)) {
                            ((HttpServletResponse)response).sendRedirect(initialPath);
                            return;
                        }
                    }

                    // make that tenant active, setup a new delegator and a new dispatcher
                    String tenantDelegatorName = delegator.getDelegatorBaseName() + "#" + tenantId;
                    httpRequest.getSession().setAttribute("delegatorName", tenantDelegatorName);

                    // after this line the delegator is replaced with the new per-tenant delegator
                    delegator = DelegatorFactory.getDelegator(tenantDelegatorName);
                    config.getServletContext().setAttribute("delegator", delegator);

                    // clear web context objects
                    config.getServletContext().setAttribute("security", null);
                    config.getServletContext().setAttribute("dispatcher", null);

                    // initialize security
                    Security security = WebAppUtil.getSecurity(config.getServletContext());
                    // initialize the services dispatcher
                    LocalDispatcher dispatcher = WebAppUtil.getDispatcher(config.getServletContext());

                    // set web context objects
                    request.setAttribute("dispatcher", dispatcher);
                    request.setAttribute("security", security);
                    
                    request.setAttribute("userTenantId", tenantId);
                }

                // NOTE DEJ20101130: do NOT always put the delegator name in the user's session because the user may 
                // have logged in and specified a tenant, and even if no Tenant record with a matching domainName field 
                // is found this will change the user's delegator back to the base one instead of the one for the 
                // tenant specified on login 
                // httpRequest.getSession().setAttribute("delegatorName", delegator.getDelegatorName());
            } catch (GenericEntityException e) {
                Debug.logWarning(e, "Unable to get Tenant", module);
            }
        }

        // we're done checking; continue on
        chain.doFilter(request, httpResponse);
    }

    /**
     * @see javax.servlet.Filter#destroy()
     */
    public void destroy() {
        WebAppUtil.getDispatcher(config.getServletContext()).deregister();
        config = null;
    }

    private void putAllInitParametersInAttributes() {
        Enumeration<String> initParamEnum = UtilGenerics.cast(config.getServletContext().getInitParameterNames());
        while (initParamEnum.hasMoreElements()) {
            String initParamName = initParamEnum.nextElement();
            String initParamValue = config.getServletContext().getInitParameter(initParamName);
            if (Debug.verboseOn()) Debug.logVerbose("Adding web.xml context-param to application attribute with name [" + initParamName + "] and value [" + initParamValue + "]", module);
            config.getServletContext().setAttribute(initParamName, initParamValue);
        }
    }

    private String getServerId() {
        String serverId = (String) config.getServletContext().getAttribute("_serverId");
        if (serverId == null) {
            serverId = config.getServletContext().getInitParameter("ofbizServerName");
            config.getServletContext().setAttribute("_serverId", serverId);
        }
        return serverId;
    }
}
