package com.chanakya.shl2.crypto;

import com.chanakya.shl2.config.ShlProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.ThumbprintUtils;
import com.nimbusds.jose.util.Base64URL;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Service
public class JwsService {

    private final ShlProperties properties;
    private ECKey signingKey;
    private String kid;
    private ECDSASigner signer;

    public JwsService(ShlProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() throws Exception {
        Resource resource = new DefaultResourceLoader().getResource(properties.shc().signingKeyPath());
        String jwkJson = resource.getContentAsString(StandardCharsets.UTF_8);
        signingKey = ECKey.parse(jwkJson);

        // Compute kid as base64url SHA-256 of JWK Thumbprint (RFC 7638)
        Base64URL thumbprint = signingKey.computeThumbprint("SHA-256");
        kid = thumbprint.toString();

        signer = new ECDSASigner(signingKey);
    }

    /**
     * Signs a DEFLATE-compressed payload as JWS with ES256.
     * Header: {alg: ES256, zip: DEF, kid: ...}
     */
    public String sign(byte[] deflatedPayload) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .customParam("zip", "DEF")
                    .keyID(kid)
                    .build();
            JWSObject jws = new JWSObject(header, new Payload(deflatedPayload));
            jws.sign(signer);
            return jws.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("JWS signing failed", e);
        }
    }

    /**
     * Returns the public key as a Map for .well-known/jwks.json.
     */
    public Map<String, Object> getPublicJwk() {
        ECKey publicKey = signingKey.toPublicJWK();
        Map<String, Object> jwk = publicKey.toJSONObject();
        jwk.put("kid", kid);
        jwk.put("use", "sig");
        jwk.put("alg", "ES256");
        return jwk;
    }
}
