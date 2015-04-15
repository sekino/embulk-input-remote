package org.embulk.input;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.LocalDestFile;
import org.embulk.config.CommitReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamFileInput;
import org.slf4j.Logger;

import javax.annotation.Nullable;

public class RemoteFileInputPlugin
		implements FileInputPlugin {
	public interface PluginTask
			extends Task {
		@Config("hosts")
		@ConfigDefault("[]")
		public List<String> getHosts();

		@Config("hosts_command")
		@ConfigDefault("null")
		public Optional<String> getHostsCommand();

		@Config("hosts_separator")
		@ConfigDefault("\" \"")
		public String getHostsSeparator();

		@Config("path")
		@ConfigDefault("\"\"")
		public String getPath();

		@Config("path_command")
		@ConfigDefault("null")
		public Optional<String> getPathCommand();

		@Config("auth")
		@ConfigDefault("{}")
		public Map<String, String> getAuth();

		@Config("last_target")
		@ConfigDefault("null")
		public Optional<Target> getLastTarget();

		public void setLastTarget(Optional<Target> lastTarget);

		public List<Target> getTargets();

		public void setTargets(List<Target> targets);

		@ConfigInject
		public BufferAllocator getBufferAllocator();
	}

	private final Logger log = Exec.getLogger(getClass());

	@Override
	public ConfigDiff transaction(ConfigSource config, FileInputPlugin.Control control) {
		PluginTask task = config.loadConfig(PluginTask.class);

		List<Target> targets = listTargets(task);
		log.info("Loading targets {}", targets);
		task.setTargets(targets);

		// number of processors is same with number of targets
		int taskCount = targets.size();
		return resume(task.dump(), taskCount, control);
	}

	private List<Target> listTargets(PluginTask task) {
		task.getHostsCommand().orNull();

		final List<String> hosts = listHosts(task);
		final String path = getPath(task);

		final ImmutableList.Builder<Target> builder = ImmutableList.builder();
		Target lastTarget = task.getLastTarget().orNull();
		for (String host : hosts) {
			Target target = new Target(host, path);
			if (lastTarget == null || target.compareTo(lastTarget) > 0) {
				builder.add(target);
			}
		}
		return builder.build();
	}

	private List<String> listHosts(PluginTask task) {
		final String hostsCommand = task.getHostsCommand().orNull();
		if (hostsCommand != null) {
			final String stdout = execCommand(hostsCommand).trim();
			return Arrays.asList(stdout.split(task.getHostsSeparator()));
		} else {
			return task.getHosts();
		}
	}

	private String getPath(PluginTask task) {
		final String pathCommand = task.getPathCommand().orNull();
		if (pathCommand != null) {
			return execCommand(pathCommand).trim();
		} else {
			return task.getPath();
		}
	}

	private String execCommand(String command) {
		ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);    // TODO: windows
		log.info("Running command {}", command);
		try {
			final Process process = pb.start();
			try (InputStream stream = process.getInputStream();
				 BufferedReader brStdout = new BufferedReader(new InputStreamReader(stream))
			) {
				String line;
				StringBuilder stdout = new StringBuilder();
				while ((line = brStdout.readLine()) != null) {
					stdout.append(line);
				}

				final int code = process.waitFor();
				if (code != 0) {
					throw new IOException(String.format(
							"Command finished with non-zero exit code. Exit code is %d.", code));
				}

				return stdout.toString();
			}
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public ConfigDiff resume(TaskSource taskSource,
							 int taskCount,
							 FileInputPlugin.Control control) {
		PluginTask task = taskSource.loadTask(PluginTask.class);

		control.run(taskSource, taskCount);

		List<Target> targets = new ArrayList<>(task.getTargets());
		Collections.sort(targets);
		return Exec.newConfigDiff().set("last_target", targets.get(targets.size() - 1));
	}

	@Override
	public void cleanup(TaskSource taskSource,
						int taskCount,
						List<CommitReport> successCommitReports) {
	}

	@Override
	public TransactionalFileInput open(TaskSource taskSource, int taskIndex) {
		PluginTask task = taskSource.loadTask(PluginTask.class);
		final Target target = task.getTargets().get(taskIndex);

		try {
			return new PluginFileInput(task, download(target, task.getAuth()));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static class PluginFileInput
			extends InputStreamFileInput
			implements TransactionalFileInput {
		// TODO create single-file InputStreamFileInput utility
		private static class RemoteFileProvider
				implements InputStreamFileInput.Provider {
			private boolean opened = false;
			private final InputStream input;

			public RemoteFileProvider(InputStream input) {
				this.input = input;
			}

			@Override
			public InputStream openNext() throws IOException {
				if (opened) {
					return null;
				}
				opened = true;
				return input;
			}

			@Override
			public void close() {
			}
		}

		public PluginFileInput(PluginTask task, InputStream input) {
			super(task.getBufferAllocator(), new RemoteFileProvider(input));
		}

		@Override
		public void abort() {
		}

		@Override
		public CommitReport commit() {
			return Exec.newCommitReport();
		}


	}

	private InputStream download(Target target, Map<String, String> auth) throws IOException {
		SSHClient client = new SSHClient();
		client.loadKnownHosts();
		InMemoryDestFileImpl memoryDestFile = new InMemoryDestFileImpl();
		try {
			client.connect(target.getHost());
			final String type = auth.get("type") != null ? auth.get("type") : "public_key";
			final String user = auth.get("user") != null ? auth.get("user") : System.getProperty("user.name");

			if ("password".equals(type)) {
				client.authPassword(user, auth.get("password"));
			} else if ("public_key".equals(type)) {
				final String key_path = auth.get("key_path");
				if (key_path == null) {
					client.authPublickey(user);
				} else {
					client.authPublickey(user, key_path);
				}
			} else {
				throw new UnsupportedOperationException("Unsupported auth type : " + type);
			}
			client.newSCPFileTransfer().download(target.getPath(), memoryDestFile);
			return new ByteArrayInputStream(memoryDestFile.outputStream.toByteArray());

		} finally {
			client.disconnect();
		}
	}

	public static class InMemoryDestFileImpl extends InMemoryDestFile {

		private ByteArrayOutputStream outputStream;

		public InMemoryDestFileImpl() {
			this.outputStream = new ByteArrayOutputStream();
		}

		@Override
		public OutputStream getOutputStream() throws IOException {
			return outputStream;
		}

		@Override
		public LocalDestFile getTargetDirectory(String dirname) throws IOException {
			return this;
		}
	}

	public static class Target implements Comparable<Target> {
		private final String host;
		private final String path;

		@JsonCreator
		public Target(
				@JsonProperty("host") String host,
				@JsonProperty("path") String path) {
			this.host = host;
			this.path = path;
		}

		public String getHost() {
			return host;
		}

		public String getPath() {
			return path;
		}

		@Override
		public int compareTo(@Nullable Target other) {
			if (other == null) {
				throw new NullPointerException();
			}
			return this.toString().compareTo(other.toString());
		}

		@Override
		public String toString() {
			return host + ":" + path;
		}
	}
}