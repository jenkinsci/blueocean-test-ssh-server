package io.jenkins.blueocean.test.ssh.command;

import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.List;

abstract class AsynchronousCommand implements Command {
    private final List<String> cmd;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback callback;
    private Thread thread;

    AsynchronousCommand(List<String> cmd) {
        this.cmd = cmd;
    }

    public void setInputStream(InputStream in) {
        this.in = in;
    }

    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    InputStream getInputStream() {
        return this.in;
    }

    OutputStream getOutputStream() {
        return this.out;
    }

    OutputStream getErrorStream() {
        return this.err;
    }

    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    public void start(Environment env) throws IOException {
        this.thread = new Thread(new Runnable() {
            public void run() {
                try {
                    int i;
                    try {
                        i = AsynchronousCommand.this.run();
                    } finally {
                        AsynchronousCommand.this.out.flush();
                        AsynchronousCommand.this.err.flush();
                    }

                    AsynchronousCommand.this.callback.onExit(i);
                } catch (Exception var8) {
                    PrintWriter ps = new PrintWriter(new OutputStreamWriter(AsynchronousCommand.this.err, Charset.defaultCharset()));
                    var8.printStackTrace(ps);
                    ps.flush();
                    AsynchronousCommand.this.callback.onExit(255, var8.getMessage());
                }

            }
        });
        this.thread.setName("SSH command: " + this.cmd);
        this.thread.start();
    }

    @SuppressWarnings("SameReturnValue")
    protected abstract int run() throws Exception;

    public void destroy() {
        if (this.thread != null) {
            this.thread.interrupt();
        }
    }
}
