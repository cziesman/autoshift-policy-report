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
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.springframework.stereotype.Component;

@Component
public class RepositorySourceFactory {

    private final AutoShiftProperties properties;

    private final Map<String, GitRepositorySource> gitSources = new ConcurrentHashMap<>();
    private final LocalGitCredentialHelper localGitCredentialHelper = new LocalGitCredentialHelper();

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
            command.setTransportConfigCallback(gitTransportConfigCallback());

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
            String path = normalizeRepositoryPath(config.getPath());
            String key = location + "@" + effectiveBranch + "@" + path;
            return gitSources.computeIfAbsent(key,
                    ignored -> new GitRepositorySource(location, effectiveBranch, path, config.getToken(),
                            properties.isRefreshOnRequest()));
        }

        Path path = Paths.get(location).toAbsolutePath().normalize();
        String repositoryPath = normalizeRepositoryPath(config.getPath());
        if (!repositoryPath.isBlank()) {
            path = path.resolve(repositoryPath).normalize();
        }
        if (!Files.isDirectory(path)) {
            throw new IOException("Repository path does not exist or is not a directory: " + path);
        }
        return new LocalRepositorySource(path);
    }

    private String normalizeRepositoryPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals(".")) {
            return "";
        }
        Path normalizedPath = Paths.get(normalized).normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            throw new IllegalArgumentException("Repository path must not escape the repository root: " + path);
        }
        return normalizedPath.toString().replace('\\', '/');
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

    private org.eclipse.jgit.api.TransportConfigCallback gitTransportConfigCallback() {
        if (properties.isGitSslVerify()) {
            return null;
        }
        return new GitTransportConfigCallback();
    }

    private java.util.Optional<CredentialsProvider> credentialsProvider(String token, String uri) {
        if (!isHttpUrl(uri)) {
            return java.util.Optional.empty();
        }

        // An explicitly configured token always wins. This is the path used by
        // the OpenShift Secret and also remains available for local overrides.
        if (token != null && !token.isBlank()) {
            return java.util.Optional.of(new UsernamePasswordCredentialsProvider("git", token));
        }

        // When no token is configured, use the same Git credential helper that
        // the local user's normal Git commands use. If no credential is available,
        // return empty so JGit can access a public repository anonymously.
        GitCredentials credentials = localGitCredentialHelper.lookup(uri);
        if (credentials == null) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new CredentialsProvider() {
            @Override
            public boolean isInteractive() {
                return false;
            }

            @Override
            public boolean supports(CredentialItem... items) {
                for (CredentialItem item : items) {
                    if (!(item instanceof CredentialItem.Username
                            || item instanceof CredentialItem.Password
                            || item instanceof CredentialItem.InformationalMessage)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean get(URIish uri, CredentialItem... items) {
                for (CredentialItem item : items) {
                    if (item instanceof CredentialItem.Username username) {
                        username.setValue(credentials.username());
                    } else if (item instanceof CredentialItem.Password password) {
                        password.setValue(credentials.password().toCharArray());
                    } else if (item instanceof CredentialItem.InformationalMessage) {
                        // The credential helper has already supplied the credentials.
                    } else {
                        return false;
                    }
                }
                return true;
            }
        });
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
        private final String repositoryPath;
        private final String token;
        private final boolean refresh;
        private Path root;
        private Git git;

        private GitRepositorySource(String uri, String branch, String repositoryPath, String token, boolean refresh) {
            this.uri = uri;
            this.branch = branch;
            this.repositoryPath = repositoryPath;
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
                    command.setTransportConfigCallback(gitTransportConfigCallback());
                    git = command.call();
                    root = repositoryPath.isBlank() ? checkout : checkout.resolve(repositoryPath).normalize();
                    if (!Files.isDirectory(root)) {
                        throw new IOException("Configured repository path does not exist in Git repository " + uri
                                + ": " + repositoryPath);
                    }
                } else if (refresh) {
                    var fetch = git.fetch().setRemote("origin");
                    credentialsProvider(token, uri).ifPresent(fetch::setCredentialsProvider);
                    fetch.setTransportConfigCallback(gitTransportConfigCallback());
                    fetch.call();
                    checkoutBranch(git, branch);
                    var pull = git.pull();
                    credentialsProvider(token, uri).ifPresent(pull::setCredentialsProvider);
                    pull.setTransportConfigCallback(gitTransportConfigCallback());
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
            return uri + " [" + branch + "]" + (repositoryPath.isBlank() ? "" : " / " + repositoryPath);
        }
    }
}
