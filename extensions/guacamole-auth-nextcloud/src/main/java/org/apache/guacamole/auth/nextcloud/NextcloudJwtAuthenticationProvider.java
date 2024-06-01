/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.nextcloud;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AbstractAuthenticationProvider;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows a pre-check of users with encrypted Nextcloud JWT data blocks.
 * The username in the JWT will be compared with a list in guacamole.properties.
 * The JWT will be verified with the public key. If the JWT is valid, the login
 * page will be loaded. If the JWT is missing or invalid, an exception message
 * will be displayed.
 */
public class NextcloudJwtAuthenticationProvider extends AbstractAuthenticationProvider {

    /**
     * The duration in minutes for which a token remains valid.
     *
     * This short validity period increases security, as the time window for potential misuse,
     * e.g. by stolen tokens, is limited. Nextcloud always generates a new valid token when the
     * Guacamole login screen will be open through the Nextcloud plugin “External sites”.
     */
    private static final int MINUTES_TOKEN_VALID = 1;
    /**
     * Injector which will manage the object graph of this authentication
     * provider.
     */
    private final Injector injector;

    @Inject
    private ConfigurationService confService;

    private static final Logger logger = LoggerFactory.getLogger(NextcloudJwtAuthenticationProvider.class);

    /**
     * Creates a new MextcloudJwtAuthenticationProvider that authenticates user.
     *
     * @throws GuacamoleException
     *     If a required property is missing, or an error occurs while parsing
     *     a property.
     */
    public NextcloudJwtAuthenticationProvider() throws GuacamoleException {

        // Set up Guice injector.
        injector = Guice.createInjector(new NextcloudJwtAuthenticationProviderModule(this));

    }

    @Override
    public String getIdentifier() {
        return "nextcloud";
    }

    @Override
    public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {

        HttpServletRequest request = credentials.getRequest();

        String token = request.getParameter("nctoken");
        String ipaddr = request.getRemoteAddr();

        boolean localAddr = this.validIpAddress(ipaddr);
        if (localAddr) {
            logger.info("Request from local address {}", ipaddr);
            return null;
        }

        if (token == null) {
            throw new GuacamoleException("Missing token.");
        }

        try {
            boolean valid = this.isValidJWT(token);
            if (!valid) {
                throw new GuacamoleException("Token expired.");
            }
            logger.info("Token valid.");
        } catch (final GuacamoleException ex) {
            logger.error("Token validation failed.", ex);
            throw new GuacamoleException(ex.getMessage());
        }
        return null;

    }

    /**
     * Validates the provided JSON Web Token (JWT).
     *
     * This method decodes the public key from a base64 encoded string, verifies the JWT using
     * the ECDSA256 algorithm, and checks the token's validity period and user permissions.
     *
     * @param token
     *     The JWT token to validate.
     * @return {@code true}
     *     If the token is valid and the user is allowed, {@code false} otherwise.
     * @throws GuacamoleException
     *     If the user is not allowed or the token is expired.
     * @throws JWTVerificationException
     *     If the token verification fails.
     * @throws NoSuchAlgorithmException
     *     If the algorithm for key generation is not available.
     * @throws InvalidKeySpecException
     *     If the key specification is invalid.
     */
    private boolean isValidJWT(final String token) throws GuacamoleException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(confService.getPublicKey());
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            ECPublicKey publicKey = (ECPublicKey) keyFactory.generatePublic(keySpec);

            JWTVerifier verifier = JWT.require(Algorithm.ECDSA256(publicKey)).build();
            DecodedJWT decodedJWT = verifier.verify(token);

            Date currentDate = new Date();
            Date maxValidDate = new Date(currentDate.getTime() - (MINUTES_TOKEN_VALID * 60 * 1000));
            boolean isUserAllowed = this.isUserAllowed(decodedJWT.getPayload());
            if (!isUserAllowed) {
                throw new GuacamoleException("User not allowed.");
            }

            boolean isValidToken = decodedJWT.getExpiresAt().after(maxValidDate);
            if (!isValidToken) {
                throw new GuacamoleException("User not allowed.");
            }

            return true;
        } catch (final JWTVerificationException | NoSuchAlgorithmException | InvalidKeySpecException ex) {
            logger.error("Token validation failed.", ex);
            return false;
        }
    }

    private boolean validIpAddress(final String ipAddress) throws GuacamoleException {

        if (confService.getTrustedNetworks().contains(ipAddress)) {
            logger.info("{} in list of allowed IP addresses.", ipAddress);
            return true;
        }
        logger.warn("{} not in list of allowed IP addresses.", ipAddress);
        return false;
    }

    /**
     * Checks if a user is allowed based on the provided payload.
     * <p>
     * This method decodes a Base64-encoded payload, parses it as JSON, and retrieves the user ID (uid)
     * from the payload. It then checks if this user is in the list of allowed users.
     * </p>
     *
     * @param payload the Base64-encoded string containing the user's data.
     * @return {@code true} if the user is allowed; {@code false} otherwise.
     */
    private boolean isUserAllowed(final String payload) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes, StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode payloadJson = objectMapper.readTree(decodedPayload);
            String uid = payloadJson.get("userdata").get("uid").asText();

            return confService.getAllowedUser().contains(uid);
        } catch (final Exception e) {
            logger.warn("User not allowed. Payload={}", payload);
            return false;
        }
    }

}
