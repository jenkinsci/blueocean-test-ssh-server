package io.jenkins.blueocean.test.ssh.command;

import org.eclipse.jgit.transport.UploadPack;

import java.util.List;

/**
 * Implements "git-upload-pack" in Jenkins SSH that lets clients
 * download commits from us.
 *
 * @author Kohsuke Kawaguchi
 */
public class UploadPackCommand extends AbstractGitCommand {
    public UploadPackCommand(List<String> cmd) {
        super(cmd);
    }

    @Override
    protected int run() throws Exception {
        UploadPack pack = new UploadPack(getRepository());
        pack.upload(getInputStream(),getOutputStream(),getErrorStream());
        return 0;
    }
}
