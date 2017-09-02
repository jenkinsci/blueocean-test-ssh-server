package io.jenkins.blueocean.test.ssh;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;

public class TestSSHServer {
    @Test
    public void testSshServer() throws IOException, GeneralSecurityException, JSchException, SftpException {
        File cwd = Files.createTempDir();
        File f2 = new File(cwd, "test.txt");
        Files.write("some-text", f2, Charset.forName("utf-8"));

        String privateKey = SSHServer.generatePrivateKey();
        String publicKey = SSHServer.getPublicKey(privateKey);

        SSHServer sshd = new SSHServer(cwd, ImmutableMap.of("bob", publicKey));
        try {
            sshd.start();

            JSch jsch = new JSch();
            jsch.addIdentity("bob", privateKey.getBytes("utf-8"), null, null);

            Session session = jsch.getSession("bob", "127.0.0.1", sshd.getPort());
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();

            ChannelSftp channelSftp = (ChannelSftp)session.openChannel("sftp");
            channelSftp.connect();

            try {
                boolean foundTestTxt = false;
                for (ChannelSftp.LsEntry entry : (Iterable<ChannelSftp.LsEntry>) channelSftp.ls(".")) {
                    if ("test.txt".equals(entry.getFilename())) {
                        foundTestTxt = true;
                        break;
                    }
                }
                Assert.assertTrue(foundTestTxt);
            } finally {
                channelSftp.disconnect();
            }

            ChannelExec channelExec = (ChannelExec)session.openChannel("exec");

            try (InputStream in = channelExec.getInputStream()) {
                channelExec.setCommand("cat test.txt");
                channelExec.connect();

                byte[] out = new byte[9];
                Assert.assertTrue(9 == in.read(out));
                Assert.assertEquals("some-text", new String(out, "utf-8"));
            } finally {
                channelExec.disconnect();
            }
        } finally {
            sshd.stop();
        }
    }
}
