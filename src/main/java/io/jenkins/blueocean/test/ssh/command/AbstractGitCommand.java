package io.jenkins.blueocean.test.ssh.command;

import org.apache.sshd.server.Command;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Implements the SSH {@link Command} for the server side git command.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class AbstractGitCommand extends AsynchronousCommand {
    private final String repoName;

    AbstractGitCommand(List<String> cmd) {
        super(cmd);
        this.repoName = cmd.get(1);
    }

    Repository getRepository() throws IOException {
        return new RepositoryBuilder().setGitDir(new File(repoName)).build();
    }
}
