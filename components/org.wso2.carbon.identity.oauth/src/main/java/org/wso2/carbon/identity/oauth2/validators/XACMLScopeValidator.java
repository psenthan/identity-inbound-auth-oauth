/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.wso2.carbon.identity.oauth2.validators;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.om.xpath.AXIOMXPath;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jaxen.JaxenException;
import org.wso2.balana.utils.exception.PolicyBuilderException;
import org.wso2.balana.utils.policy.PolicyBuilder;
import org.wso2.balana.utils.policy.dto.RequestElementDTO;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.entitlement.EntitlementException;
import org.wso2.carbon.identity.entitlement.PDPConstants;
import org.wso2.carbon.identity.entitlement.common.EntitlementPolicyConstants;
import org.wso2.carbon.identity.entitlement.common.dto.RequestDTO;
import org.wso2.carbon.identity.entitlement.common.dto.RowDTO;
import org.wso2.carbon.identity.entitlement.common.util.PolicyCreatorUtil;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;

import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamException;

/**
 * The Scope Validation implementation. This uses XACML policies to evaluate scope validation defined by the user.
 */
public class XACMLScopeValidator extends OAuth2ScopeValidator {

    private static final String DECISION_XPATH = "//ns:Result/ns:Decision/text()";
    private static final String XACML_NS = "urn:oasis:names:tc:xacml:3.0:core:schema:wd-17";
    private static final String XACML_NS_PREFIX = "ns";
    private static final String RULE_EFFECT_PERMIT = "Permit";
    private static final String RULE_EFFECT_NOT_APPLICABLE = "NotApplicable";
    private static final String ACTION_VALIDATE = "token_validation";

    private static final String ACTION_CATEGORY = "http://wso2.org/identity/identity-action";
    private static final String SP_CATEGORY = "http://wso2.org/identity/sp";
    private static final String USER_CATEGORY = "http://wso2.org/identity/user";
    private static final String SCOPE_CATEGORY = "http://wso2.org/identity/oauth-scope";

    private static final String AUTH_ACTION_ID = ACTION_CATEGORY + "/action-name";
    private static final String SP_NAME_ID = SP_CATEGORY + "/sp-name";
    private static final String USERNAME_ID = USER_CATEGORY + "/username";
    private static final String USER_STORE_ID = USER_CATEGORY + "/user-store-domain";
    private static final String USER_TENANT_DOMAIN_ID = USER_CATEGORY + "/user-tenant-domain";
    private static final String SCOPE_ID = SCOPE_CATEGORY + "/scope-name";

    private Log log = LogFactory.getLog(XACMLScopeValidator.class);

    @Override
    public boolean validateScope(AccessTokenDO accessTokenDO, String resource) throws IdentityOAuth2Exception {

        if (accessTokenDO.getAuthzUser() == null) {
            if (log.isDebugEnabled()) {
                log.debug("Invalid access token");
            }
            return false;
        }

        if (log.isDebugEnabled()) {
            log.debug("In policy scope validation flow...");
        }

        try {
            String consumerKey = accessTokenDO.getConsumerKey();
            OAuthAppDO authApp = OAuth2Util.getAppInformationByClientId(consumerKey);
            String tenantDomainOfOauthApp = OAuth2Util.getTenantDomainOfOauthApp(authApp);

            Boolean isValidationEnabled = OAuth2Util.getIsValidationEnabledOfOauthApp(authApp.getApplicationName(),
                    consumerKey, tenantDomainOfOauthApp);


            if (!isValidationEnabled) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Scope validation is disabled for '%s@%s'",
                            authApp.getApplicationName(), tenantDomainOfOauthApp));
                }
                return true;
            }

            return validateScope(accessTokenDO, authApp, resource, tenantDomainOfOauthApp);

        } catch (InvalidOAuthClientException e) {
            log.error("Invalid OAuth Client Exception occurred", e);
        } catch (IdentityApplicationManagementException e) {
            log.error("Identity Application Management Exception occurred", e);
        }
        return false;
    }

    /**
     * Use entitlement service to validate the scope of the token against the XACML policies.
     *
     * @param accessTokenDO          access token to be validated
     * @param authApp                auth application of that token
     * @param resource               resource to which the client is trying to access
     * @param tenantDomainOfOauthApp tenant domain of the application
     * @return
     */
    private boolean validateScope(AccessTokenDO accessTokenDO, OAuthAppDO authApp, String resource, String tenantDomainOfOauthApp) {
        boolean isValidated = false;
        try {
            RequestDTO requestDTO = createRequestDTO(accessTokenDO, authApp, resource);
            RequestElementDTO requestElementDTO = PolicyCreatorUtil.createRequestElementDTO(requestDTO);
            String requestString = PolicyBuilder.getInstance().buildRequest(requestElementDTO);

            if (log.isDebugEnabled()) {
                log.debug("XACML scope validation request :\n" + requestString);
            }
            FrameworkUtils.startTenantFlow(accessTokenDO.getAuthzUser().getTenantDomain());
            String responseString =
                    OAuth2ServiceComponentHolder.getEntitlementService().getDecision(requestString);
            if (log.isDebugEnabled()) {
                log.debug("XACML scope validation response :\n" + responseString);
            }
            String validationResponse = evaluateXACMLResponse(responseString);
            if (RULE_EFFECT_NOT_APPLICABLE.equalsIgnoreCase(validationResponse)) {
                log.warn(String.format(
                        "No applicable rule for service provider '%s@%s', Hence validating the token by default." +
                                "Add an validating policy (or unset validation) to fix this warning.",
                        authApp.getApplicationName(), tenantDomainOfOauthApp));
                isValidated = true;
            } else if (RULE_EFFECT_PERMIT.equalsIgnoreCase(validationResponse)) {
                isValidated = true;
            }
        } catch (PolicyBuilderException e) {
            log.error("Policy Builder Exception occurred", e);
        } catch (XMLStreamException | JaxenException e) {
            log.error("Exception occurred when getting decision from xacml response", e);
        } catch (EntitlementException e) {
            log.error("Entitlement Exception occurred", e);
        } finally {
            FrameworkUtils.endTenantFlow();
        }
        return isValidated;
    }

    /**
     * Creates request dto object
     *
     * @param accessTokenDO access token
     * @param authApp       OAuth app
     * @param resource      resource
     * @return RequestDTO
     */
    private RequestDTO createRequestDTO(AccessTokenDO accessTokenDO, OAuthAppDO authApp, String resource) {
        List<RowDTO> rowDTOs = new ArrayList<>();
        RowDTO actionDTO =
                createRowDTO(ACTION_VALIDATE,
                        AUTH_ACTION_ID, ACTION_CATEGORY);
        RowDTO spNameDTO =
                createRowDTO(authApp.getApplicationName(),
                        SP_NAME_ID, SP_CATEGORY);
        RowDTO usernameDTO =
                createRowDTO(accessTokenDO.getAuthzUser().getUserName(),
                        USERNAME_ID, USER_CATEGORY);
        RowDTO userStoreDomainDTO =
                createRowDTO(accessTokenDO.getAuthzUser().getUserStoreDomain(),
                        USER_STORE_ID, USER_CATEGORY);
        RowDTO userTenantDomainDTO =
                createRowDTO(accessTokenDO.getAuthzUser().getTenantDomain(),
                        USER_TENANT_DOMAIN_ID, USER_CATEGORY);
        RowDTO resourceDTO = createRowDTO(resource, EntitlementPolicyConstants.RESOURCE_ID,
                PDPConstants.RESOURCE_CATEGORY_URI);

        rowDTOs.add(actionDTO);
        rowDTOs.add(spNameDTO);
        rowDTOs.add(usernameDTO);
        rowDTOs.add(userStoreDomainDTO);
        rowDTOs.add(userTenantDomainDTO);
        rowDTOs.add(resourceDTO);

        for (String scope : accessTokenDO.getScope()) {
            RowDTO scopeNameDTO =
                    createRowDTO(scope,
                            SCOPE_ID, SCOPE_CATEGORY);
            rowDTOs.add(scopeNameDTO);
        }
        RequestDTO requestDTO = new RequestDTO();
        requestDTO.setRowDTOs(rowDTOs);
        return requestDTO;
    }

    /**
     * Creates RowDTO object of xacml request
     *
     * @param resourceName  resource name
     * @param attributeId   attribute id of the resource
     * @param categoryValue category of the resource
     * @return RowDTO
     */
    private RowDTO createRowDTO(String resourceName, String attributeId, String categoryValue) {

        RowDTO rowDTO = new RowDTO();
        rowDTO.setAttributeValue(resourceName);
        rowDTO.setAttributeDataType(EntitlementPolicyConstants.STRING_DATA_TYPE);
        rowDTO.setAttributeId(attributeId);
        rowDTO.setCategory(categoryValue);
        return rowDTO;

    }

    /**
     * This extracts the decision from the xacml response
     *
     * @param xacmlResponse xacml response to be extracted
     * @return extracted decision
     * @throws XMLStreamException exception when converting string response to XML
     * @throws JaxenException     exception
     */
    private String evaluateXACMLResponse(String xacmlResponse) throws XMLStreamException, JaxenException {

        AXIOMXPath axiomxPath = new AXIOMXPath(DECISION_XPATH);
        axiomxPath.addNamespace(XACML_NS_PREFIX, XACML_NS);
        OMElement rootElement =
                new StAXOMBuilder(new ByteArrayInputStream(xacmlResponse.getBytes(StandardCharsets.UTF_8)))
                        .getDocumentElement();
        return axiomxPath.stringValueOf(rootElement);

    }
}