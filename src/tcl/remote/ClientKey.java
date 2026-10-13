package tcl.remote;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/** Always present our certificate. The TV is not a public CA. */
final class ClientKey extends X509ExtendedKeyManager {
    private final Cert identity;

    ClientKey(Cert identity) {
        this.identity = identity;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return "c";
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return "c";
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return new X509Certificate[] {identity.cert};
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return identity.key;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[] {"c"};
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return null;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return null;
    }
}
