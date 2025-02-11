/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iiordanov.pubkeygenerator;

import android.content.ContentValues;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * @author Kenny Root
 */
public class PubkeyBean extends AbstractBean {
    public static final String BEAN_NAME = "pubkey";

    private static final String KEY_TYPE_ECDSA = PubkeyDatabase.KEY_TYPE_ECDSA;
    private static final String KEY_TYPE_RSA = PubkeyDatabase.KEY_TYPE_RSA;
    private static final String KEY_TYPE_DSA = PubkeyDatabase.KEY_TYPE_DSA;

    /* Database fields */
    private long id;
    private String nickname;
    private String type;
    private byte[] privateKey;
    private PublicKey publicKey;
    private boolean encrypted = false;
    private boolean startup = false;
    private boolean confirmUse = false;
    private int lifetime = 0;

    /* Transient values */
    private boolean unlocked = false;
    private Object unlockedPrivate = null;

    @Override
    public String getBeanName() {
        return BEAN_NAME;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public byte[] getPrivateKey() {
        if (privateKey == null)
            return null;
        else
            return privateKey.clone();
    }

    public void setPrivateKey(byte[] privateKey) {
        if (privateKey == null)
            this.privateKey = null;
        else
            this.privateKey = privateKey.clone();
    }

    private PublicKey decodePublicKeyAs(EncodedKeySpec keySpec, String keyType) {
        try {
            final KeyFactory kf = KeyFactory.getInstance(keyType);
            return kf.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            return null;
        } catch (InvalidKeySpecException e) {
            return null;
        }
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(byte[] encoded) {
        if (encoded == null) return;
        final X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(encoded);
        if (type != null) {
            publicKey = decodePublicKeyAs(pubKeySpec, type);
        } else {
            publicKey = decodePublicKeyAs(pubKeySpec, KEY_TYPE_RSA);
            if (publicKey != null) {
                type = KEY_TYPE_RSA;
            } else {
                publicKey = decodePublicKeyAs(pubKeySpec, KEY_TYPE_DSA);
                if (publicKey != null) {
                    type = KEY_TYPE_DSA;
                } else {
                    publicKey = decodePublicKeyAs(pubKeySpec, KEY_TYPE_ECDSA);
                    if (publicKey != null) {
                        type = KEY_TYPE_ECDSA;
                    }
                }
            }
        }
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public boolean isStartup() {
        return startup;
    }

    public void setStartup(boolean startup) {
        this.startup = startup;
    }

    public boolean isConfirmUse() {
        return confirmUse;
    }

    public void setConfirmUse(boolean confirmUse) {
        this.confirmUse = confirmUse;
    }

    public int getLifetime() {
        return lifetime;
    }

    public void setLifetime(int lifetime) {
        this.lifetime = lifetime;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public void setUnlocked(boolean unlocked) {
        this.unlocked = unlocked;
    }

    public Object getUnlockedPrivate() {
        return unlockedPrivate;
    }

    public void setUnlockedPrivate(Object unlockedPrivate) {
        this.unlockedPrivate = unlockedPrivate;
    }

    public String getDescription() {
        StringBuilder sb = new StringBuilder();
        if (publicKey instanceof RSAPublicKey) {
            int bits = ((RSAPublicKey) publicKey).getModulus().bitLength();
            sb.append("RSA ");
            sb.append(bits);
            sb.append("-bit");
        } else if (publicKey instanceof DSAPublicKey) {
            sb.append("DSA 1024-bit");
        } else {
            sb.append("Unknown Key Type");
        }

        if (encrypted)
            sb.append(" (encrypted)");

        return sb.toString();
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bssh.bean.AbstractBean#getValues()
     */
    @Override
    public ContentValues getValues() {
        ContentValues values = new ContentValues();

        values.put(PubkeyDatabase.FIELD_PUBKEY_NICKNAME, nickname);
        values.put(PubkeyDatabase.FIELD_PUBKEY_TYPE, type);
        values.put(PubkeyDatabase.FIELD_PUBKEY_PRIVATE, privateKey);
        if (publicKey != null)
            values.put(PubkeyDatabase.FIELD_PUBKEY_PUBLIC, publicKey.getEncoded());
        values.put(PubkeyDatabase.FIELD_PUBKEY_ENCRYPTED, encrypted ? 1 : 0);
        values.put(PubkeyDatabase.FIELD_PUBKEY_STARTUP, startup ? 1 : 0);
        values.put(PubkeyDatabase.FIELD_PUBKEY_CONFIRMUSE, confirmUse ? 1 : 0);
        values.put(PubkeyDatabase.FIELD_PUBKEY_LIFETIME, lifetime);

        return values;
    }

    public boolean changePassword(String oldPassword, String newPassword) throws Exception {
        PrivateKey priv;

        try {
            priv = PubkeyUtils.decodePrivate(getPrivateKey(), getType(), oldPassword);
        } catch (Exception e) {
            return false;
        }

        setPrivateKey(PubkeyUtils.getEncodedPrivate(priv, newPassword));
        setEncrypted(newPassword.length() > 0);

        return true;
    }
}
