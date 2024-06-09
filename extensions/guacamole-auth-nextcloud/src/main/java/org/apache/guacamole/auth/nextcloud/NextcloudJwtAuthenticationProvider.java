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
import org.apache.guacamole.GuacamoleSecurityException;
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

    /**
     * The configuration service for this module.
     */
    @Inject
    private ConfigurationService confService;

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(NextcloudJwtAuthenticationProvider.class);

    /**
     * Creates a new NextcloudJwtAuthenticationProvider that authenticates user.
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

    /**
     * Authenticates a user based on the provided credentials.
     *
     * @param
     *     credentials The credentials containing the user's authentication data.
     *
     * @return
     *     AuthenticatedUser The authenticated user, or null if the request is from a local address.
     *
     * @throws
     *     GuacamoleException If there is an issue with the authentication process.
     *
     * @throws
     *     GuacamoleSecurityException If the JWT is invalid.
     */
    @Override
    public AuthenticatedUser authenticateUser(Credentials credentials) throws GuacamoleException {

        // Retrieve the HTTP request and extract the token and ip address.
        HttpServletRequest request = credentials.getRequest();
        String token = request.getParameter(confService.getTokenName());
        String ipaddr = request.getRemoteAddr();

        // If the request from ip address is allowed, jwt authentication is not required.
        boolean localAddr = this.validIpAddress(ipaddr);
        if (localAddr) {
            logger.info("Request from local address {}", ipaddr);
            return null;
        }

        // Fails if the token is not present or has not been found.
        if (token == null) {
            throw new GuacamoleException("Missing token.");
        }

        try {
            this.validateJwt(token);
            logger.info("Token valid.");
        }
        catch (final GuacamoleException ex) {
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
     *
     * @throws GuacamoleException
     *     If public key cannot be parsed or is missing.
     *
     * @throws GuacamoleSecurityException
     *     If the user is not authorized, the token has expired or the validation itself fails.
     */
    private void validateJwt(final String token) throws GuacamoleException {
        try {
            // Decode the Base64 encoded public key from configuration and generate the ECPublicKey object.
            byte[] keyBytes = Base64.getDecoder().decode(confService.getPublicKey());
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            ECPublicKey publicKey = (ECPublicKey) keyFactory.generatePublic(keySpec);

            // Create a JWT verifier instance with the provided public key, verify the token and decode the content.
            JWTVerifier verifier = JWT.require(Algorithm.ECDSA256(publicKey)).build();
            DecodedJWT decodedJWT = verifier.verify(token);

            // Check if the user extracted from the token's payload is allowed to open Guacamole login page.
            boolean isUserAllowed = this.isUserAllowed(decodedJWT.getPayload());
            if (!isUserAllowed) {
                throw new GuacamoleSecurityException("User not allowed.");
            }

            // Validate the token's expiration by comparing the current date with the token's expiration date,
            // ensuring it falls within the acceptable validity duration defined by MINUTES_TOKEN_VALID.
            Date currentDate = new Date();
            Date maxValidDate = new Date(currentDate.getTime() - (MINUTES_TOKEN_VALID * 60 * 1000));
            boolean isValidToken = decodedJWT.getExpiresAt().after(maxValidDate);
            if (!isValidToken) {
                throw new GuacamoleSecurityException("Token expired.");
            }
        }
        catch (final NoSuchAlgorithmException | InvalidKeySpecException ex) {
            logger.error("Token validation failed.", ex);
            throw new GuacamoleException(ex.getMessage());
        }
    }

    /**
     * Validates whether an IP address is allowed based on the configured trusted networks.
     * <p>
     * This method checks if the given IP address is within the range of any configured trusted networks.
     * If the list of trusted networks is empty, the method returns {@code true}, indicating that all IP addresses
     * are allowed.
     * </p>
     *
     * @param ipAddress
     *     The IP address to validate.
     *
     * @return {@code true}
     *     If the IP address is allowed or if the list of trusted networks is empty; {@code false} otherwise.
     *
     * @throws GuacamoleException
     *     If an error occurs while accessing the configuration service.
     */
    private boolean validIpAddress(final String ipAddress) throws GuacamoleException {
        // allow all ip addresses if not restricted
        if (confService.getTrustedNetworks().isEmpty()) {
            return true;
        }

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
     * from the payload. It then checks if this user is in the list of allowed users. If the list of allowed
     * users is empty, the method returns {@code true}, indicating that the user is not restricted.
     * </p>
     *
     * @param payload
     *     The Base64-encoded string containing the user's data.
     *
     * @return {@code true}
     *     If the user is allowed or if the list of allowed users is empty; {@code false} otherwise.
     */
    private boolean isUserAllowed(final String payload) {
        try {
            // allow all users if not restricted
            if (confService.getAllowedUser().isEmpty()) {
                return true;
            }

            byte[] decodedBytes = Base64.getDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes, StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode payloadJson = objectMapper.readTree(decodedPayload);
            String uid = payloadJson.get("userdata").get("uid").asText();

            return confService.getAllowedUser().contains(uid);
        }
        catch (final Exception e) {
            logger.warn("User not allowed. Payload={}", payload);
            return false;
        }
    }

}