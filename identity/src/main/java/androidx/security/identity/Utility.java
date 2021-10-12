/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.security.identity;

import android.content.Context;
import android.icu.util.Calendar;
import android.util.Pair;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.builder.ArrayBuilder;
import co.nstant.in.cbor.builder.MapBuilder;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;

/**
 * Miscellaneous utility functions that are useful when building mdoc applications.
 */
public class Utility {
    private static final String TAG = "Utility";

    /**
     * Helper to encode digest-id mapping and issuerAuth CBOR into a single byte array.
     *
     * <p>The resulting byte array can be stored as <code>staticAuthData</code> using
     * {@link IdentityCredential#storeStaticAuthenticationData(X509Certificate, Calendar, byte[])}
     * and returned using {@link ResultData#getStaticAuthenticationData()} at presentation time.
     *
     * <p>Use {@link #decodeStaticAuthData(byte[])} for the reverse operation.
     *
     * <p>The returned data are the bytes of CBOR with the following CDDL:
     * <pre>
     *     StaticAuthData = {
     *         "digestIdMapping": DigestIdMapping,
     *         "issuerAuth" : IssuerAuth
     *     }
     *
     *     DigestIdMapping = {
     *         NameSpace => [ + IssuerSignedItemBytes ]
     *     }
     *
     *     ; Defined in ISO 18013-5
     *     ;
     *     NameSpace = String
     *     DataElementIdentifier = String
     *     DigestID = uint
     *     IssuerAuth = COSE_Sign1 ; The payload is MobileSecurityObjectBytes
     *
     *     IssuerSignedItemBytes = #6.24(bstr .cbor IssuerSignedItem)
     *
     *     IssuerSignedItem = {
     *       "digestID" : uint,                           ; Digest ID for issuer data auth
     *       "random" : bstr,                             ; Random value for issuer data auth
     *       "elementIdentifier" : DataElementIdentifier, ; Data element identifier
     *       "elementValue" : DataElementValue            ; Data element value
     *     }
     * </pre>
     *
     * @param issuerSignedMapping A mapping from data-elements to digest-id and randoms.
     *                            The elementValue entry must be present but set to the NULL
     *                            value.
     * @param encodedIssuerAuth   the bytes of <code>COSE_Sign1</code> signed by the issuing
     *                            authority and where the payload is set to
     *                            <code>MobileSecurityObjectBytes</code>.
     * @return the bytes of the CBOR described above.
     */
    public static @NonNull
    byte[] encodeStaticAuthData(
            @NonNull Map<String, List<byte[]>> issuerSignedMapping,
            @NonNull byte[] encodedIssuerAuth) {

        CborBuilder builder = new CborBuilder();
        MapBuilder<CborBuilder> outerBuilder = builder.addMap();
        for (Map.Entry<String, List<byte[]>> oe : issuerSignedMapping.entrySet()) {
            String ns = oe.getKey();
            ArrayBuilder<MapBuilder<CborBuilder>> innerBuilder = outerBuilder.putArray(ns);
            for (byte[] encodedIssuerSignedItemBytes : oe.getValue()) {
                // Strictly not necessary but check that elementValue is NULL. This is to
                // avoid applications (or issuers) sending the value in issuerSignedMapping
                // which is part of staticAuthData. This would be bad because then the
                // data element value would be available without any access control checks.
                //
                DataItem issuerSignedItemBytes = Util.cborDecode(encodedIssuerSignedItemBytes);
                DataItem issuerSignedItem =
                        Util.cborExtractTaggedAndEncodedCbor(issuerSignedItemBytes);
                DataItem value = Util.cborMapExtract(issuerSignedItem, "elementValue");
                if (!(value instanceof SimpleValue)
                        || ((SimpleValue) value).getSimpleValueType() != SimpleValueType.NULL) {
                    String name = Util.cborMapExtractString(issuerSignedItem, "elementIdentifier");
                    throw new IllegalArgumentException("elementValue for nameSpace " + ns
                            + " elementName " + name + " is not NULL");
                }

                innerBuilder.add(encodedIssuerSignedItemBytes);
            }
        }
        DataItem digestIdMappingItem = builder.build().get(0);

        byte[] staticAuthData = Util.cborEncode(new CborBuilder()
                .addMap()
                .put(new UnicodeString("digestIdMapping"), digestIdMappingItem)
                .put(new UnicodeString("issuerAuth"), Util.cborDecode(encodedIssuerAuth))
                .end()
                .build().get(0));
        return staticAuthData;
    }

    /**
     * Helper to decode <code>staticAuthData</code> in the format specified by the
     * {@link #encodeStaticAuthData(Map, byte[])} method.
     *
     * @param staticAuthData the bytes of CBOR as described above.
     * @return <code>issuerSignedMapping</code> and <code>encodedIssuerAuth</code>.
     * @throws IllegalArgumentException if the given data is not in the format specified by the
     *                                  {@link #encodeStaticAuthData(Map, byte[])} method.
     */
    public static @NonNull Pair<Map<String, List<byte[]>>, byte[]>
    decodeStaticAuthData(@NonNull byte[] staticAuthData) {
        DataItem topMapItem = Util.cborDecode(staticAuthData);
        if (!(topMapItem instanceof co.nstant.in.cbor.model.Map)) {
            throw new IllegalArgumentException("Top-level is not a map");
        }
        co.nstant.in.cbor.model.Map topMap = (co.nstant.in.cbor.model.Map) topMapItem;
        DataItem issuerAuthItem = topMap.get(new UnicodeString("issuerAuth"));
        if (issuerAuthItem == null) {
            throw new IllegalArgumentException("issuerAuth item does not exist");
        }
        byte[] encodedIssuerAuth = Util.cborEncode(issuerAuthItem);

        Map<String, List<byte[]>> buildOuterMap = new HashMap<>();

        DataItem outerMapItem = topMap.get(new UnicodeString("digestIdMapping"));
        if (!(outerMapItem instanceof co.nstant.in.cbor.model.Map)) {
            throw new IllegalArgumentException(
                    "digestIdMapping value is not a map or does not exist");
        }
        co.nstant.in.cbor.model.Map outerMap = (co.nstant.in.cbor.model.Map) outerMapItem;
        for (DataItem outerKey : outerMap.getKeys()) {
            if (!(outerKey instanceof UnicodeString)) {
                throw new IllegalArgumentException("Outer key is not a string");
            }
            String ns = ((UnicodeString) outerKey).getString();

            List<byte[]> buildInnerArray = new ArrayList<>();
            buildOuterMap.put(ns, buildInnerArray);

            DataItem outerValue = outerMap.get(outerKey);
            if (!(outerValue instanceof co.nstant.in.cbor.model.Array)) {
                throw new IllegalArgumentException("Outer value is not an array");
            }
            co.nstant.in.cbor.model.Array innerArray = (co.nstant.in.cbor.model.Array) outerValue;
            for (DataItem innerKey : innerArray.getDataItems()) {
                if (!(innerKey instanceof ByteString)) {
                    throw new IllegalArgumentException("Inner key is not a bstr");
                }
                byte[] encodedIssuerSignedItemBytes = ((ByteString) innerKey).getBytes();

                // Strictly not necessary but check that elementValue is NULL. This is to
                // avoid applications (or issuers) sending the value in issuerSignedMapping
                // which is part of staticAuthData. This would be bad because then the
                // data element value would be available without any access control checks.
                //
                DataItem issuerSignedItemBytes = Util.cborDecode(encodedIssuerSignedItemBytes);
                DataItem issuerSignedItem =
                        Util.cborExtractTaggedAndEncodedCbor(issuerSignedItemBytes);
                DataItem value = Util.cborMapExtract(issuerSignedItem, "elementValue");
                if (!(value instanceof SimpleValue)
                        || ((SimpleValue) value).getSimpleValueType() != SimpleValueType.NULL) {
                    String name = Util.cborMapExtractString(issuerSignedItem, "elementIdentifier");
                    throw new IllegalArgumentException("elementValue for nameSpace " + ns
                            + " elementName " + name + " is not NULL");
                }

                buildInnerArray.add(encodedIssuerSignedItemBytes);
            }
        }

        return new Pair<>(buildOuterMap, encodedIssuerAuth);
    }


    /**
     * Helper function to create a self-signed credential, including authentication keys and
     * static authentication data.
     *
     * <p>The created authentication keys will have associated <code>staticAuthData</code>
     * which is encoded in the same format as returned by
     * the {@link #encodeStaticAuthData(Map, byte[])} helper method meaning that at
     * presentation-time the {@link #decodeStaticAuthData(byte[])} can be used to recover the
     * digest-id mapping and <code>IssuerAuth</code> CBOR and both can be fed directly into
     * {@link PresentationHelper#deviceResponseAddDocument(
     * PresentationHelper.DocumentRequest, ResultData, ResultData, Map, byte[])}.
     *
     * <p>This helper is useful only when developing mdoc applications that are not yet
     * using a live issuing authority.
     *
     * @param store the {@link IdentityCredentialStore} to create the credential in.
     * @param credentialName name to use for the credential, e.g. "test".
     * @param issuingAuthorityKey the private key to use for signing the static auth data.
     * @param issuingAuthorityCertificate the certificate corresponding the signing key.
     * @param numAuthKeys the number of authentication keys to create.
     * @param docType the document type of the credential, e.g. "org.iso.18013.5.1.mDL".
     * @param personalizationData the data to put in the document, organized by namespace.
     * @param numAuthKeys number of authentication keys to create.
     * @param maxUsesPerKey number of uses for each authentication key.
     * @throws IdentityCredentialException
     */
    public static void provisionSelfSignedCredential(
            @NonNull IdentityCredentialStore store,
            @NonNull String credentialName,
            @NonNull PrivateKey issuingAuthorityKey,
            @NonNull X509Certificate issuingAuthorityCertificate,
            @NonNull String docType,
            @NonNull PersonalizationData personalizationData,
            int numAuthKeys,
            int maxUsesPerKey) throws IdentityCredentialException {

        final byte[] provisioningChallenge = "dummyChallenge".getBytes(StandardCharsets.UTF_8);

        store.deleteCredentialByName(credentialName);
        WritableIdentityCredential wc = null;
        try {
            wc = store.createCredential(credentialName, docType);
        } catch (AlreadyPersonalizedException e) {
            e.printStackTrace();
            return;
        } catch (DocTypeNotSupportedException e) {
            e.printStackTrace();
            return;
        }
        Collection<X509Certificate> certificateChain =
                wc.getCredentialKeyCertificateChain(provisioningChallenge);

        byte[] proofOfProvisioningSignature = wc.personalize(personalizationData);

        IdentityCredential c = store.getCredentialByName(credentialName,
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);

        c.setAvailableAuthenticationKeys(numAuthKeys, maxUsesPerKey);
        Collection<X509Certificate> authKeysNeedCert = c.getAuthKeysNeedingCertification();

        Calendar signedDate = Calendar.getInstance();
        Calendar validFromDate = Calendar.getInstance();
        Calendar validToDate = Calendar.getInstance();
        validToDate.add(Calendar.MONTH, 12);

        for (X509Certificate authKeyCert : authKeysNeedCert) {
            PublicKey authKey = authKeyCert.getPublicKey();

            Random r = new SecureRandom();

            // Count number of entries and generate digest ids
            int numEntries = 0;
            for (PersonalizationData.NamespaceData nsd : personalizationData.getNamespaceDatas()) {
                numEntries += nsd.getEntryNames().size();
            }
            List<Integer> digestIds = new ArrayList<>();
            for (int n = 0; n < numEntries; n++) {
                digestIds.add(n);
            }
            Collections.shuffle(digestIds);

            HashMap<String, List<byte[]>> issuerSignedMapping = new HashMap<>();

            CborBuilder vdBuilder = new CborBuilder();
            MapBuilder<CborBuilder> vdMapBuilder = vdBuilder.addMap();

            Iterator<Integer> digestIt = digestIds.iterator();
            for (PersonalizationData.NamespaceData nsd : personalizationData.getNamespaceDatas()) {
                String ns = nsd.getNamespaceName();

                List<byte[]> innerArray = new ArrayList<>();

                MapBuilder<MapBuilder<CborBuilder>> vdInner = vdMapBuilder.putMap(ns);

                for (String entry : nsd.getEntryNames()) {
                    byte[] encodedValue = nsd.getEntryValue(entry);
                    int digestId = digestIt.next();
                    byte[] random = new byte[16];
                    r.nextBytes(random);
                    DataItem value = Util.cborDecode(encodedValue);

                    byte[] encodedIssuerSignedItemBytes =
                            Util.cborEncode(Util.calcIssuerSignedItemBytes(digestId,
                                    random,
                                    entry,
                                    value));
                    byte[] digest = null;
                    try {
                        digest = MessageDigest.getInstance("SHA-256").digest(encodedIssuerSignedItemBytes);
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalArgumentException("Failed creating digester", e);
                    }

                    // Replace elementValue in encodedIssuerSignedItemBytes with NULL value.
                    //
                    byte[] encodedIssuerSignedItemBytesCleared =
                            Util.issuerSignedItemBytesClearValue(encodedIssuerSignedItemBytes);
                    innerArray.add(encodedIssuerSignedItemBytesCleared);

                    vdInner.put(digestId, digest);
                }

                issuerSignedMapping.put(ns, innerArray);

                vdInner.end();
            }
            vdMapBuilder.end();

            byte[] encodedMobileSecurityObject = Util.cborEncode(new CborBuilder()
                    .addMap()
                    .put("version", "1.0")
                    .put("digestAlgorithm", "SHA-256")
                    .put(new UnicodeString("valueDigests"), vdBuilder.build().get(0))
                    .put("docType", docType)
                    .putMap("validityInfo")
                    .put(new UnicodeString("signed"), Util.cborBuildDateTimeFor18013_5(signedDate))
                    .put(new UnicodeString("validFrom"), Util.cborBuildDateTimeFor18013_5(validFromDate))
                    .put(new UnicodeString("validUntil"),
                            Util.cborBuildDateTimeFor18013_5(validToDate))
                    .end()
                    .putMap("deviceKeyInfo")
                    .put(new UnicodeString("deviceKey"), Util.cborBuildCoseKey(authKey))
                    .end()
                    .end()
                    .build().get(0));

            byte[] taggedEncodedMso = Util.cborEncode(
                    Util.cborBuildTaggedByteString(encodedMobileSecurityObject));

            // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
            //
            // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
            //
            ArrayList<X509Certificate> issuerAuthorityCertChain = new ArrayList<>();
            issuerAuthorityCertChain.add(issuingAuthorityCertificate);
            byte[] encodedIssuerAuth =
                    Util.cborEncode(Util.coseSign1Sign(issuingAuthorityKey,
                            taggedEncodedMso,
                            null,
                            issuerAuthorityCertChain));

            // Store issuerSignedMapping and issuerAuth (the MSO) in staticAuthData...
            //
            byte[] staticAuthData = encodeStaticAuthData(
                    issuerSignedMapping, encodedIssuerAuth);
            c.storeStaticAuthenticationData(authKeyCert,
                    validToDate,
                    staticAuthData);

        } // for each authkey

    }

    /**
     * @hide
     */
    static IdentityCredentialStore getIdentityCredentialStore(@NonNull Context context) {
        // We generally want to run all tests against the software implementation since
        // hardware-based implementations are already tested against CTS and VTS and the bulk
        // of the code in the Jetpack is the software implementation. This also helps avoid
        // whatever bugs or flakiness that may exist in hardware implementations.
        //
        // Occasionally it's useful for a developer to test that the hardware-backed paths
        // (HardwareIdentityCredentialStore + friends) work as intended. This can be done by
        // uncommenting the line below and making sure it runs on a device with the appropriate
        // hardware support.
        //
        // See b/164480361 for more discussion.
        //
        //return IdentityCredentialStore.getHardwareInstance(context);
        return IdentityCredentialStore.getSoftwareInstance(context);
    }


    public static @NonNull
    Map<String, List<byte[]>> mergeIssuerSigned(
            @NonNull Map<String, List<byte[]>> issuerSignedMapping,
            @NonNull CredentialDataResult.Entries issuerSigned) {

        Map<String, List<byte[]>> newIssuerSignedMapping = new HashMap<>();

        for (String namespaceName : issuerSigned.getNamespaces()) {
            List<byte[]> newEncodedIssuerSignedItemBytesForNs = new ArrayList<>();

            List<byte[]> encodedIssuerSignedItemBytesForNs = issuerSignedMapping.get(namespaceName);
            if (encodedIssuerSignedItemBytesForNs == null) {
                throw new IllegalArgumentException("No namespace " + namespaceName
                        + " in given issuerSignedMapping");
            }

            Collection<String> entryNames = issuerSigned.getEntryNames(namespaceName);
            for (byte[] encodedIssuerSignedItemBytes : encodedIssuerSignedItemBytesForNs) {
                DataItem issuerSignedItemBytes = Util.cborDecode(encodedIssuerSignedItemBytes);
                DataItem issuerSignedItem =
                        Util.cborExtractTaggedAndEncodedCbor(issuerSignedItemBytes);
                String elemName = Util.cborMapExtractString(issuerSignedItem, "elementIdentifier");

                if (!entryNames.contains(elemName)) {
                    continue;
                }
                byte[] elemValue = issuerSigned.getEntry(namespaceName, elemName);
                if (elemValue != null) {
                    byte[] encodedIssuerSignedItemBytesWithValue =
                            Util.issuerSignedItemBytesSetValue(encodedIssuerSignedItemBytes,
                                    elemValue);
                    newEncodedIssuerSignedItemBytesForNs.add(encodedIssuerSignedItemBytesWithValue);
                }
            }
            if (newEncodedIssuerSignedItemBytesForNs.size() > 0) {
                newIssuerSignedMapping.put(namespaceName, newEncodedIssuerSignedItemBytesForNs);
            }
        }
        return newIssuerSignedMapping;
    }

}