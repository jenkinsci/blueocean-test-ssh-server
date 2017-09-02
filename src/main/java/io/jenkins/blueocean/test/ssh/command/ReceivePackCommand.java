package io.jenkins.blueocean.test.ssh.command;

import org.eclipse.jgit.transport.ReceivePack;

import java.util.List;

/**
 * Implements "git-receive-pack" in Jenkins SSH that receives uploaded commits from clients.
 *
 * @author Kohsuke Kawaguchi
 */
public class ReceivePackCommand extends AbstractGitCommand {
    public ReceivePackCommand(List<String> cmd) {
        super(cmd);
    }

    @Override
    protected int run() throws Exception {
        ReceivePack pack = new ReceivePack(getRepository());
        pack.receive(getInputStream(),getOutputStream(),getErrorStream());
        return 0;
    }
}
