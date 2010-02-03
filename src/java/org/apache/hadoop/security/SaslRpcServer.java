/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.security;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.TreeMap;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.TokenIdentifier;

/**
 * A utility class for dealing with SASL on RPC server
 */
public class SaslRpcServer {
  public static final Log LOG = LogFactory.getLog(SaslRpcServer.class);
  public static final String SASL_DEFAULT_REALM = "default";
  public static final Map<String, String> SASL_PROPS = 
      new TreeMap<String, String>();
  static {
    // Request authentication plus integrity protection
    SASL_PROPS.put(Sasl.QOP, "auth-int");
    // Request mutual authentication
    SASL_PROPS.put(Sasl.SERVER_AUTH, "true");
  }

  static String encodeIdentifier(byte[] identifier) {
    return new String(Base64.encodeBase64(identifier));
  }

  static byte[] decodeIdentifier(String identifier) {
    return Base64.decodeBase64(identifier.getBytes());
  }

  static char[] encodePassword(byte[] password) {
    return new String(Base64.encodeBase64(password)).toCharArray();
  }

  /** Splitting fully qualified Kerberos name into parts */
  public static String[] splitKerberosName(String fullName) {
    return fullName.split("[/@]");
  }

  /** Authentication method */
  public static enum AuthMethod {
    SIMPLE((byte) 80, ""), // no authentication
    KERBEROS((byte) 81, "GSSAPI"), // SASL Kerberos authentication
    DIGEST((byte) 82, "DIGEST-MD5"); // SASL DIGEST-MD5 authentication

    /** The code for this method. */
    public final byte code;
    public final String mechanismName;

    private AuthMethod(byte code, String mechanismName) {
      this.code = code;
      this.mechanismName = mechanismName;
    }

    private static final int FIRST_CODE = values()[0].code;

    /** Return the object represented by the code. */
    private static AuthMethod valueOf(byte code) {
      final int i = (code & 0xff) - FIRST_CODE;
      return i < 0 || i >= values().length ? null : values()[i];
    }

    /** Return the SASL mechanism name */
    public String getMechanismName() {
      return mechanismName;
    }

    /** Read from in */
    public static AuthMethod read(DataInput in) throws IOException {
      return valueOf(in.readByte());
    }

    /** Write to out */
    public void write(DataOutput out) throws IOException {
      out.write(code);
    }
  };

  /** CallbackHandler for SASL DIGEST-MD5 mechanism */
  public static class SaslDigestCallbackHandler implements CallbackHandler {
    private SecretManager<TokenIdentifier> secretManager;

    public SaslDigestCallbackHandler(
        SecretManager<TokenIdentifier> secretManager) {
      this.secretManager = secretManager;
    }

    private TokenIdentifier getIdentifier(String id) throws IOException {
      byte[] tokenId = decodeIdentifier(id);
      TokenIdentifier tokenIdentifier = secretManager.createIdentifier();
      tokenIdentifier.readFields(new DataInputStream(new ByteArrayInputStream(
          tokenId)));
      return tokenIdentifier;
    }

    private char[] getPassword(TokenIdentifier tokenid) throws IOException {
      return encodePassword(secretManager.retrievePassword(tokenid));
    }

    /** {@inheritDoc} */
    @Override
    public void handle(Callback[] callbacks) throws IOException,
        UnsupportedCallbackException {
      NameCallback nc = null;
      PasswordCallback pc = null;
      AuthorizeCallback ac = null;
      for (Callback callback : callbacks) {
        if (callback instanceof AuthorizeCallback) {
          ac = (AuthorizeCallback) callback;
        } else if (callback instanceof NameCallback) {
          nc = (NameCallback) callback;
        } else if (callback instanceof PasswordCallback) {
          pc = (PasswordCallback) callback;
        } else if (callback instanceof RealmCallback) {
          continue; // realm is ignored
        } else {
          throw new UnsupportedCallbackException(callback,
              "Unrecognized SASL DIGEST-MD5 Callback");
        }
      }
      if (pc != null) {
        TokenIdentifier tokenIdentifier = getIdentifier(nc.getDefaultName());
        char[] password = getPassword(tokenIdentifier);
        if (LOG.isDebugEnabled()) {
          LOG.debug("SASL server DIGEST-MD5 callback: setting password "
              + "for client: " + tokenIdentifier.getUsername());
        }
        pc.setPassword(password);
      }
      if (ac != null) {
        String authid = ac.getAuthenticationID();
        String authzid = ac.getAuthorizationID();
        if (authid.equals(authzid)) {
          ac.setAuthorized(true);
        } else {
          ac.setAuthorized(false);
        }
        if (ac.isAuthorized()) {
          String username = getIdentifier(authzid).getUsername().toString();
          if (LOG.isDebugEnabled())
            LOG.debug("SASL server DIGEST-MD5 callback: setting "
                + "canonicalized client ID: " + username);
          ac.setAuthorizedID(username);
        }
      }
    }
  }

  /** CallbackHandler for SASL GSSAPI Kerberos mechanism */
  public static class SaslGssCallbackHandler implements CallbackHandler {

    /** {@inheritDoc} */
    @Override
    public void handle(Callback[] callbacks) throws IOException,
        UnsupportedCallbackException {
      AuthorizeCallback ac = null;
      for (Callback callback : callbacks) {
        if (callback instanceof AuthorizeCallback) {
          ac = (AuthorizeCallback) callback;
        } else {
          throw new UnsupportedCallbackException(callback,
              "Unrecognized SASL GSSAPI Callback");
        }
      }
      if (ac != null) {
        String authid = ac.getAuthenticationID();
        String authzid = ac.getAuthorizationID();
        if (authid.equals(authzid)) {
          ac.setAuthorized(true);
        } else {
          ac.setAuthorized(false);
        }
        if (ac.isAuthorized()) {
          if (LOG.isDebugEnabled())
            LOG.debug("SASL server GSSAPI callback: setting "
                + "canonicalized client ID: " + authzid);
          ac.setAuthorizedID(authzid);
        }
      }
    }
  }
}