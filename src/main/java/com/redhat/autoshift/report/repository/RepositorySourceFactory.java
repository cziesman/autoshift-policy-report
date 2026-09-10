package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.redhat.autoshift.report.config.AutoShiftProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

@Component
public class RepositorySourceFactory {

    private final AutoShiftProperties properties;

    private final Map<String, GitRepositorySource> gitSources = new ConcurrentHashMap<>();

    public RepositorySourceFactory(AutoShiftProperties properties) {
        this.properties = properties;
    }

    public RepositorySource policies() throws IOException {
        return policies(properties.getPolicies().getBranch());
    }

    public RepositorySource policies(String branch) throws IOException {
        return source(properties.getPolicies(), branch);
    }

    public RepositorySource siteValues() throws IOException {
        return siteValues(properties.getSiteValues().getBranch());
    }

    public RepositorySource siteValues(String branch) throws IOException {
        return source(properties.getSiteValues(), branch);
    }

    public Set<String> availablePolicyBranches() throws IOException {
        return branches(properties.getPolicies());
    }

    public Set<String> availableSiteValuesBranches() throws IOException {
        return branches(properties.getSiteValues());
    }

    private Set<String> branches(AutoShiftProperties.RepositoryProperties config) throws IOException {
        String location = config.getLocation();
        if (location == null || location.isBlank()) {
            throw new IOException("Repository location must not be empty");
        }

        if (!isGitLocation(location)) {
            return Set.of(configuredBranch(config));
        }

        try {
            var command = Git.lsRemoteRepository()
                    .setHeads(true)
                    .setTags(false)
                    .setRemote(location);
            credentialsProvider(config.getToken(), location).ifPresent(command::setCredentialsProvider);

            Set<String> result = new TreeSet<>();
            for (Ref ref : command.call()) {
                String name = ref.getName();
                if (name.startsWith("refs/heads/")) {
                    result.add(name.substring("refs/heads/".length()));
                }
            }
            return result.isEmpty() ? Set.of(configuredBranch(config)) : result;
        } catch (GitAPIException e) {
            throw new IOException("Unable to list branches for Git repository " + location, e);
        }
    }

    private RepositorySource source(AutoShiftProperties.RepositoryProperties config, String branch) throws IOException {
        String location = config.getLocation();
        if (location == null || location.isBlank()) {
            throw new IOException("Repository location must not be empty");
        }

        String effectiveBranch = branch == null || branch.isBlank()
                ? configuredBranch(config)
                : branch;

        if (isGitLocation(location)) {
            String key = location + "@" + effectiveBranch;
            return gitSources.computeIfAbsent(key,
                    ignored -> new GitRepositorySource(location, effectiveBranch, config.getToken(),
                            properties.isRefreshOnRequest()));
        }

        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException("Repository path does not exist or is not a directory: " + path);
        }
        return new LocalRepositorySource(path);
    }

    private String configuredBranch(AutoShiftProperties.RepositoryProperties config) {
        return config.getBranch() == null || config.getBranch().isBlank() ? "main" : config.getBranch();
    }

    private boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isGitLocation(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("ssh://") || lower.startsWith("git://")
                || lower.startsWith("git@");
    }

    private java.util.Optional<CredentialsProvider> credentialsProvider(String token, String uri) {
        if (token == null || token.isBlank() || !isHttpUrl(uri)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new UsernamePasswordCredentialsProvider("git", token));
    }

    private static final class LocalRepositorySource implements RepositorySource {
        private final Path root;

        private LocalRepositorySource(Path root) {
            this.root = root;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
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
                    credentialsProvider(token, uri).ifPresent(command::setCredentialsProvider);
                    git = command.call();
                    root = checkout;
                } else if (refresh) {
                    var fetch = git.fetch().setRemote("origin");
                    credentialsProvider(token, uri).ifPresent(fetch::setCredentialsProvider);
                    fetch.call();
                    checkoutBranch(git, branch);
                    var pull = git.pull();
                    credentialsProvider(token, uri).ifPresent(pull::setCredentialsProvider);
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

        @Override
        public String displayName() {
            return uri + " [" + branch + "]";
        }
    }
}
