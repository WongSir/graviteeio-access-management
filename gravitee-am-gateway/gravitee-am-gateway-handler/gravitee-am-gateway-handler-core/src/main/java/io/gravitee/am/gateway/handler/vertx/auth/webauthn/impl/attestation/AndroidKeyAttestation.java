/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright 2019 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.attestation;

import io.gravitee.am.gateway.handler.vertx.auth.jose.JWK;
import io.gravitee.am.gateway.handler.vertx.auth.webauthn.PublicKeyCredential;
import io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.AuthData;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.webauthn.impl.attestation.AttestationException;

import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import static io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.attestation.ASN1.*;
import static io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.attestation.Attestation.parseX5c;
import static io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.attestation.Attestation.verifySignature;

/**
 * Implementation of the "android-key" attestation check.
 * <p>
 * Android KeyStore is a key management container, that defends key material from extraction.
 * Depending on the device, it can be either software or hardware backed.
 * <p>
 * For example if authenticator required to be FIPS/CC/PCI/FIDO compliant, then it needs
 * to be running on the device with FIPS/CC/PCI/FIDO compliant hardware, and it can be
 * found getting KeyStore attestation.
 *
 * @author <a href="mailto:pmlopes@gmail.com>Paulo Lopes</a>
 */
// TODO to remove when updating to vert.x 4
public class AndroidKeyAttestation implements io.gravitee.am.gateway.handler.vertx.auth.webauthn.impl.attestation.Attestation {

    /* Android Keystore Root is not published anywhere.
     * This certificate was extracted from one of the attestations
     * The last certificate in x5c must match this certificate
     * This needs to be checked to ensure that malicious party wont generate fake attestations
     */
    private static final String ANDROID_KEYSTORE_ROOT =
            "MIICizCCAjKgAwIBAgIJAKIFntEOQ1tXMAoGCCqGSM49BAMCMIGYMQswCQYDVQQG" +
                    "EwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmll" +
                    "dzEVMBMGA1UECgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMTMwMQYD" +
                    "VQQDDCpBbmRyb2lkIEtleXN0b3JlIFNvZnR3YXJlIEF0dGVzdGF0aW9uIFJvb3Qw" +
                    "HhcNMTYwMTExMDA0MzUwWhcNMzYwMTA2MDA0MzUwWjCBmDELMAkGA1UEBhMCVVMx" +
                    "EzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxFTAT" +
                    "BgNVBAoMDEdvb2dsZSwgSW5jLjEQMA4GA1UECwwHQW5kcm9pZDEzMDEGA1UEAwwq" +
                    "QW5kcm9pZCBLZXlzdG9yZSBTb2Z0d2FyZSBBdHRlc3RhdGlvbiBSb290MFkwEwYH" +
                    "KoZIzj0CAQYIKoZIzj0DAQcDQgAE7l1ex-HA220Dpn7mthvsTWpdamguD_9_SQ59" +
                    "dx9EIm29sa_6FsvHrcV30lacqrewLVQBXT5DKyqO107sSHVBpKNjMGEwHQYDVR0O" +
                    "BBYEFMit6XdMRcOjzw0WEOR5QzohWjDPMB8GA1UdIwQYMBaAFMit6XdMRcOjzw0W" +
                    "EOR5QzohWjDPMA8GA1UdEwEB_wQFMAMBAf8wDgYDVR0PAQH_BAQDAgKEMAoGCCqG" +
                    "SM49BAMCA0cAMEQCIDUho--LNEYenNVg8x1YiSBq3KNlQfYNns6KGYxmSGB7AiBN" +
                    "C_NR2TB8fVvaNTQdqEcbY6WFZTytTySn502vQX3xvw";

    private static final JsonArray EMPTY = new JsonArray(Collections.emptyList());

    @Override
    public String fmt() {
        return "android-key";
    }

    @Override
    public void validate(Metadata metadata, JsonObject webauthn, byte[] clientDataJSON, JsonObject attestation, AuthData authData) throws AttestationException {
        // Typical attestation object
        //{
        //    "fmt": "android-key",
        //    "authData": "base64",
        //    "attStmt": {
        //        "alg": -7,
        //        "sig": "base64",
        //        "x5c": [
        //            "base64",
        //            "base64",
        //            "base64"
        //        ]
        //    }
        //}

        try {
            byte[] clientDataHash = Attestation.hash("SHA-256", clientDataJSON);

            // Verifying attestation
            // 1. Concatenate authData with clientDataHash to create signatureBase
            byte[] signatureBase = Buffer.buffer()
                    .appendBytes(authData.getRaw())
                    .appendBytes(clientDataHash)
                    .getBytes();
            // 2. Verify signature sig over the signatureBase using
            //    public key extracted from leaf certificate in x5c
            JsonObject attStmt = attestation.getJsonObject("attStmt");
            byte[] signature = attStmt.getBinary("sig");
            List<X509Certificate> certChain = parseX5c(attStmt.getJsonArray("x5c"));
            if (certChain.size() == 0) {
                throw new AttestationException("Invalid certificate chain");
            }

            final X509Certificate leafCert = certChain.get(0);

            // verify the signature
            verifySignature(
                    PublicKeyCredential.valueOf(attStmt.getInteger("alg")),
                    leafCert,
                    signature,
                    signatureBase);

            // meta data check
            JsonObject statement = metadata.verifyMetadata(
                    authData.getAaguidString(),
                    PublicKeyCredential.valueOf(attStmt.getInteger("alg")),
                    certChain);

            // Verifying attestation certificate
            // 1. Check that authData publicKey matches the public key in the attestation certificate
            JWK coseKey = authData.getCredentialJWK();
            if (!leafCert.getPublicKey().equals(coseKey.getPublicKey())) {
                throw new AttestationException("Certificate public key does not match public key in authData!");
            }
            // 2. Find Android KeyStore Extension with OID “1.3.6.1.4.1.11129.2.1.17” in certificate extensions.
            ASN attestationExtension = ASN1.parseASN1(Buffer.buffer(leafCert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17")));
            if (attestationExtension.tag.type != ASN1.OCTET_STRING) {
                throw new AttestationException("Attestation Extension is not an ASN.1 OCTECT string!");
            }
            // parse the octec as ASN.1 and expect it to se a sequence
            attestationExtension = ASN1.parseASN1(Buffer.buffer(attestationExtension.binary(0)));
            if (attestationExtension.tag.type != ASN1.SEQUENCE) {
                throw new AttestationException("Attestation Extension Value is not an ASN.1 SEQUENCE!");
            }
            // get the data at index 4 (certificate challenge)
            byte[] data = attestationExtension.object(4).binary(0);

            // 3. Check that attestationChallenge is set to the clientDataHash.
            // verify that the client hash matches the certificate hash
            if (!MessageDigest.isEqual(clientDataHash, data)) {
                throw new AttestationException("Certificate attestation challenge is not set to the clientData hash!");
            }
            // 4. Check that both teeEnforced and softwareEnforced structures don’t contain allApplications(600) tag.
            // This is important as the key must strictly bound to the caller app identifier.
            ASN1.ASN softwareEnforcedAuthz = attestationExtension.object(6);
            for (Object object : softwareEnforcedAuthz.value) {
                if (object instanceof ASN1.ASN) {
                    // verify if the that the list doesn't contain "allApplication" 600 flag
                    if (((ASN1.ASN) object).tag.number == 600) {
                        throw new AttestationException("Software authorisation list contains 'allApplication' flag, which means that credential is not bound to the RP!");
                    }
                }
            }
            // 4. Check that both teeEnforced and softwareEnforced structures don’t contain allApplications(600) tag.
            // This is important as the key must strictly bound to the caller app identifier.
            ASN1.ASN teeEnforcedAuthz = attestationExtension.object(7);
            for (Object object : teeEnforcedAuthz.value) {
                if (object instanceof ASN1.ASN) {
                    // verify if the that the list doesn't contain "allApplication" 600 flag
                    if (((ASN1.ASN) object).tag.number == 600) {
                        throw new AttestationException("TEE authorisation list contains 'allApplication' flag, which means that credential is not bound to the RP!");
                    }
                }
            }

            if (statement == null || statement.getJsonArray("attestationRootCertificates", EMPTY).size() == 0) {
                // 5. Check that root certificate(last in the chain) is set to the root certificate
                // Google does not publish this certificate, so this was extracted from one of the attestations.
                if (!ANDROID_KEYSTORE_ROOT.equals(attStmt.getJsonArray("x5c").getString(attStmt.getJsonArray("x5c").size() - 1))) {
                    throw new AttestationException("Root certificate is invalid!");
                }
            }

        } catch (CertificateException | InvalidKeyException | SignatureException | NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException e) {
            throw new AttestationException(e);
        }
    }
}
