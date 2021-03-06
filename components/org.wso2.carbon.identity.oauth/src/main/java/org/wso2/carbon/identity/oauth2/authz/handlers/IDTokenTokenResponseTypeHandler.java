/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.oauth2.authz.handlers;

import org.apache.oltu.oauth2.common.message.types.ResponseType;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.authz.OAuthAuthzReqMessageContext;
import org.wso2.carbon.identity.oauth2.authz.handlers.util.ResponseTypeHandlerUtil;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AuthorizeRespDTO;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;

/**
 * IDTokenTokenResponseTypeHandler class creates an access token and id_token when the response type is equal to
 * "id_token token".
 */
public class IDTokenTokenResponseTypeHandler extends IDTokenResponseTypeHandler {

    @Override
    public OAuth2AuthorizeRespDTO issue(OAuthAuthzReqMessageContext oauthAuthzMsgCtx) throws IdentityOAuth2Exception {

        // Starting to trigger pre listeners.
        ResponseTypeHandlerUtil.triggerPreListeners(oauthAuthzMsgCtx);
        // Generating access token.
        AccessTokenDO accessTokenDO = ResponseTypeHandlerUtil.generateAccessToken(oauthAuthzMsgCtx, cacheEnabled,
                oauthIssuerImpl);
        // Generating response for id_token token flow.
        OAuth2AuthorizeRespDTO respDTO = buildResponseDTO(oauthAuthzMsgCtx, accessTokenDO);
        // Starting to trigger post listeners.
        ResponseTypeHandlerUtil.triggerPostListeners(oauthAuthzMsgCtx, accessTokenDO, respDTO);
        return  respDTO;
    }

    private OAuth2AuthorizeRespDTO buildResponseDTO(OAuthAuthzReqMessageContext oauthAuthzMsgCtx, AccessTokenDO accessTokenDO) throws IdentityOAuth2Exception {
        // Initializing the response.
        OAuth2AuthorizeRespDTO respDTO = initResponse(oauthAuthzMsgCtx);
        // Add id_token details to the response.
        ResponseTypeHandlerUtil.buildIDTokenResponseDTO(respDTO, accessTokenDO, oauthAuthzMsgCtx);
        // Add access token details to the response.
        return ResponseTypeHandlerUtil.buildAccessTokenResponseDTO(respDTO, accessTokenDO);

    }

}
