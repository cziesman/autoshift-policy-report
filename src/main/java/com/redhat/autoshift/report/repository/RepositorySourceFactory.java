package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.redhat.autoshift.report.config.AutoShiftProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.stereotype.Component;

@Component
public class RepositorySourceFactory {

    private final AutoShiftProperties properties;

    private final Map<String, GitRepositorySource> gitSources = new ConcurrentHashMap<>();

    public RepositorySourceFactory(AutoShiftProperties properties) {

        this.properties = properties;
    }

    public RepositorySource policies() throws IOException {

        return source(properties.getPolicies());
    }

    public RepositorySource siteValues() throws IOException {

        return source(properties.getSiteValues());
    }

    private RepositorySource source(AutoShiftProperties.RepositoryProperties config) throws IOException {

        String location = config.getLocation();
        if (location == null || location.isBlank()) {
            throw new IOException("Repository location must not be empty");
        }
        if (isUrl(location)) {
            return gitSources.computeIfAbsent(location + "@" + config.getBranch(),
                    key -> new GitRepositorySource(location, config.getBranch(), config.getToken(), properties.isRefreshOnRequest()));
        }
        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException("Repository path does not exist or is not a directory: " + path);
        }
        return new LocalRepositorySource(path);
    }

    private boolean isHttpUrl(String value) {

        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isUrl(String value) {

        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("ssh://") || lower.startsWith("git://") ||
                lower.startsWith("git@") || lower.startsWith("file://");
    }

    private static final class LocalRepositorySource implements RepositorySource {

        private final Path root;

        private LocalRepositorySource(Path root) {

            this.root = root;
        }

        public Path root() {

            return root;
        }

        public String displayName() {

            return root.toString();
        }

    }

    private final class GitRepositorySource implements RepositorySource {

        private final String uri;

        private final String branch;

        private final String token;

        private final boolean refresh;

        private Path root;

        private Git git;

        private GitRepositorySource(String uri, String branch, String token, boolean refresh) {

            this.uri = uri;
            this.branch = branch;
            this.token = token;
            this.refresh = refresh;
        }

        @Override
        public synchronized Path root() throws IOException {

            try {
                if (git == null) {
                    Path work = Files.createTempDirectory("autoshift-policy-report-");
                    Path checkout = work.resolve("repo");
                    var command = Git.cloneRepository()
                            .setURI(uri)
                            .setDirectory(checkout.toFile())
                            .setBranch(branch);
                    credentialsProvider().ifPresent(command::setCredentialsProvider);
                    git = command.call();
                    root = checkout;
                } else if (refresh) {
                    var fetch = git.fetch().setRemote("origin");
                    credentialsProvider().ifPresent(fetch::setCredentialsProvider);
                    fetch.call();
                    checkoutBranch(git, branch);
                    var pull = git.pull();
                    credentialsProvider().ifPresent(pull::setCredentialsProvider);
                    pull.call();
                }
                return root;
            } catch (GitAPIException e) {
                throw new IOException("Unable to access Git repository " + uri + " on branch " + branch, e);
            }
        }

        private void checkoutBranch(Git repository, String branch) throws GitAPIException, IOException {

            String ref = "refs/heads/" + branch;
            if (repository.getRepository().findRef(ref) != null) {
                repository.checkout().setName(branch).call();
            } else {
                repository.checkout()
                        .setCreateBranch(true)
                        .setName(branch)
                        .setStartPoint("origin/" + branch)
                        .call();
            }
        }

        private java.util.Optional<CredentialsProvider> credentialsProvider() {

            if (token == null || token.isBlank() || !isHttpUrl(uri)) {
                return java.util.Optional.empty();
            }
            // GitHub accepts the token as the HTTP basic password; the username is ignored.
            return java.util.Optional.of(new UsernamePasswordCredentialsProvider("git", token));
        }

        @Override
        public String displayName() {

            return uri + " [" + branch + "]";
        }

    }

}
