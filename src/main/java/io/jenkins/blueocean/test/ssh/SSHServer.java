package io.jenkins.blueocean.test.ssh;

import com.google.common.collect.ImmutableList;
import org.apache.commons.codec.binary.Base64;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.random.JceRandomFactory;
import org.apache.sshd.common.random.SingletonRandomFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuth;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.AbstractGeneratorHostKeyProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SSHServer {
    private static final Logger logger = Logger.getLogger(SSHShell.class.getName());

    private final SshServer sshd;

    public SSHServer(final File cwd, final File keyFile, final int port, final boolean allowLocalUser, final Map<String, String> authorizedUsers) {
        // Set up sshd defaults, bind go IPv4 and random non-privileged port
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("0.0.0.0");
        sshd.setPort(port);

        // Set up an RSA host key
        AbstractGeneratorHostKeyProvider hostKeyProvider = new SimpleGeneratorHostKeyProvider(keyFile);
        hostKeyProvider.setAlgorithm("RSA");
        sshd.setKeyPairProvider(hostKeyProvider);

        // Set key exchange factories so recent clients can connect
//        sshd.setKeyExchangeFactories(Arrays.<NamedFactory<KeyExchange>>asList(
//            BuiltinDHFactories.dhg14, // this is only registered by default with BC provider... JCE doesn't support 2048 bit?
//            BuiltinDHFactories.dhg1));
        sshd.setRandomFactory(new SingletonRandomFactory(new JceRandomFactory()));

        sshd.setShellFactory(new SSHProcessFactory(cwd));

        // Set up git + scp command support
        CommandFactory gitCommandFactory = new GitCommandFactory(cwd);
        ScpCommandFactory factory = new ScpCommandFactory();
        factory.setDelegateCommandFactory(gitCommandFactory);
        sshd.setCommandFactory(factory);

        // Set up the user's SSH key for authentication
        PublickeyAuthenticator authenticator = new PublickeyAuthenticator() {
            @Override
            public boolean authenticate(String username, PublicKey key, ServerSession session) {
                try {
                    byte[] incoming = encodePublicKey((RSAPublicKey)key);
                    String incomingHex = Base64.encodeBase64String(incoming);
                    if (allowLocalUser) {
                        File localPublicKey = new File(System.getProperty("user.home") + "/.ssh/id_rsa.pub");
                        if(localPublicKey.canRead() && new String(Files.readAllBytes(localPublicKey.toPath()), "utf-8").contains(incomingHex)) {
                            return true;
                        }
                    }
                    if (authorizedUsers.containsKey(username)) {
                        String userPublicKey = authorizedUsers.get(username);
                        logger.fine(" ---- Authentication request for: " + username + " with key: " + incomingHex + " user's public key is: " + userPublicKey);
                        return userPublicKey == null || incomingHex.equals(userPublicKey);
                    }
                    return false;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        sshd.setPublickeyAuthenticator(authenticator);
        sshd.setUserAuthFactories(Collections.<NamedFactory<UserAuth>>singletonList(new UserAuthPublicKeyFactory()));

        final RootedFileSystemProvider rootFsProvider = new RootedFileSystemProvider();
        sshd.setFileSystemFactory(new NativeFileSystemFactory() {
            @Override
            public FileSystem createFileSystem(Session session) throws IOException {
                return rootFsProvider.newFileSystem(cwd.toPath(), Collections.<String, Object>emptyMap());
            }
        });

        sshd.setSubsystemFactories(ImmutableList.<NamedFactory<Command>>of(new SftpSubsystemFactory()));

        sshd.setTcpipForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        PropertyResolverUtils.updateProperty(sshd, "welcome-banner", "Welcome to SSHD\n");
    }

    public void start() throws IOException {
        sshd.start();
    }

    public void stop() throws IOException {
        sshd.stop();
    }

    public int getPort() {
        return sshd.getPort();
    }

    static class GitCommandFactory implements CommandFactory {
        final File cwd;

        GitCommandFactory(File cwd) {
            this.cwd = cwd;
        }

        @Override
        public Command createCommand(String command) {
            logger.fine("Incoming command: " + command);
            List<String> cmd = new ArrayList<>(Arrays.asList(command.split(" ")));
            for (int i = 0; i < cmd.size(); i++) {
                String part = cmd.get(i);
                part = part.replaceAll("[']", "");
                cmd.set(i, part);
            }
            return new SSHProcessFactory(cwd, cmd).create();
        }
    }

    /**
     * Encodes the public key according to some spec somewhere
     * @param key public key to use
     * @return the ssh-rsa bytes
     */
    private static byte[] encodePublicKey(RSAPublicKey key) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            /* encode the "ssh-rsa" string */
            byte[] sshrsa = new byte[] { 0, 0, 0, 7, 's', 's', 'h', '-', 'r', 's', 'a' };
            out.write(sshrsa);
            /* Encode the public exponent */
            BigInteger e = key.getPublicExponent();
            byte[] data = e.toByteArray();
            encodeUInt32(data.length, out);
            out.write(data);
            /* Encode the modulus */
            BigInteger m = key.getModulus();
            data = m.toByteArray();
            encodeUInt32(data.length, out);
            out.write(data);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void encodeUInt32(int value, OutputStream out) throws IOException {
        byte[] tmp = new byte[4];
        tmp[0] = (byte) ((value >>> 24) & 0xff);
        tmp[1] = (byte) ((value >>> 16) & 0xff);
        tmp[2] = (byte) ((value >>> 8) & 0xff);
        tmp[3] = (byte) (value & 0xff);
        out.write(tmp);
    }
}
