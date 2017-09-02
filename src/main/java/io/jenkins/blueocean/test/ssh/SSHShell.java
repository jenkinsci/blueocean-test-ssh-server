package io.jenkins.blueocean.test.ssh;

import io.jenkins.blueocean.test.ssh.command.AsynchronousCommand;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.PuttyRequestHandler;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.session.ServerSessionHolder;
import org.apache.sshd.server.shell.InvertedShell;
import org.apache.sshd.server.shell.TtyFilterInputStream;
import org.apache.sshd.server.shell.TtyFilterOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

class SSHShell implements InvertedShell, ServerSessionHolder {
    private final Logger log;
    private final List<String> command;
    private final File cwd;
    private String cmdValue;
    private ServerSession session;
    private Process process;
    private TtyFilterOutputStream in;
    private TtyFilterInputStream out;
    private TtyFilterInputStream err;
    private final boolean interactive;
    private int exitValue;

    public SSHShell(Logger log, File cwd, boolean interactive, List<String> command) {
        this.log = log;
        this.command = new ArrayList<>(ValidateUtils.checkNotNullAndNotEmpty(command, "No process shell command(s)"));
        this.cmdValue = GenericUtils.join(command, ' ');
        this.cwd = cwd;
        this.interactive = interactive;
    }

    public ServerSession getServerSession() {
        return this.session;
    }

    public void setSession(ServerSession session) {
        this.session = Objects.requireNonNull(session, "No server session");
        ValidateUtils.checkTrue(this.process == null, "Session set after process started");
    }

    public void start(final Environment env) throws IOException {
        Map<String, String> varsMap = this.resolveShellEnvironment(env.getEnv());

        for(int i = 0; i < this.command.size(); ++i) {
            String cmd = this.command.get(i);
            if ("$USER".equals(cmd)) {
                cmd = varsMap.get("USER");
                this.command.set(i, cmd);
                this.cmdValue = GenericUtils.join(this.command, ' ');
            }
        }

        ProcessBuilder builder = new ProcessBuilder(this.command);
        builder.directory(cwd);
        Map modes;
        if (GenericUtils.size(varsMap) > 0) {
            try {
                modes = builder.environment();
                modes.putAll(varsMap);
            } catch (Exception var5) {
                this.log.warning("start() - Failed (" + var5.getClass().getSimpleName() + ") to set environment for command=" + this.cmdValue + ": " + var5.getMessage());
                this.log.log(Level.FINEST, "start(" + this.cmdValue + ") failure details", var5);
            }
        }

        this.log.fine("Starting shell with command: '" + builder.command() + "' and env: " + builder.environment());

        this.process = builder.start();
        modes = this.resolveShellTtyOptions(env.getPtyModes());

        if (interactive) {
            if (OsUtils.isUNIX()) {
                modes.put(PtyMode.ECHO, 0);
                modes.put(PtyMode.ONLCR, 1);
                modes.put(PtyMode.ECHOCTL, 1);
            } else {
                modes.put(PtyMode.ECHO, 1);
                modes.put(PtyMode.ICRNL, 1);
                modes.put(PtyMode.ONLCR, 1);
            }
        }

        this.out = new TtyFilterInputStream(this.process.getInputStream(), modes);
        this.err = new TtyFilterInputStream(this.process.getErrorStream(), modes);
        this.in = new TtyFilterOutputStream(this.process.getOutputStream(), this.err, modes);
    }

    private Map<String, String> resolveShellEnvironment(Map<String, String> env) {
        return env;
    }

    private Map<PtyMode, Integer> resolveShellTtyOptions(Map<PtyMode, Integer> modes) {
        return PuttyRequestHandler.isPuttyClient(this.getServerSession()) ? PuttyRequestHandler.resolveShellTtyOptions(modes) : modes;
    }

    public OutputStream getInputStream() {
        return this.in;
    }

    public InputStream getOutputStream() {
        return this.out;
    }

    public InputStream getErrorStream() {
        return this.err;
    }

    public boolean isAlive() {
        return this.process != null;
    }

    public int exitValue() {
        if (this.isAlive()) {
            try {
                return this.process.waitFor();
            } catch (InterruptedException var2) {
                throw new RuntimeException(var2);
            }
        } else {
            return this.exitValue;
        }
    }

    public void destroy() {
        if (this.process != null) {
            this.log.fine("Destroy process for " + this.cmdValue);
            this.process.destroy();
            this.exitValue = this.process.exitValue();
            this.process = null;
        }

        IOException e = IoUtils.closeQuietly(this.getInputStream(), this.getOutputStream(), this.getErrorStream());
        if (e != null) {
            this.log.fine(e.getClass().getSimpleName() + " while destroy streams of '" + this + "': " + e.getMessage());
            for (Throwable t : e.getSuppressed()) {
                this.log.fine("Suppressed " + t.getClass().getSimpleName() + ") while destroy streams of '" + this + "': " + t.getMessage());
            }
        }

    }

    public String toString() {
        return GenericUtils.isEmpty(this.cmdValue) ? super.toString() : this.cmdValue;
    }
}
