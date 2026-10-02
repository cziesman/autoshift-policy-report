package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Resolves HTTP(S) credentials through the user's normal Git credential helper.
 *
 * This intentionally delegates to the Git executable instead of reading a
 * particular credential-store format. That allows configured helpers such as
 * macOS osxkeychain, Git Credential Manager, libsecret, or the file helper to
 * be used without the application knowing where credentials are stored.
 */
final class LocalGitCredentialHelper {

    private final String gitExecutable;

    LocalGitCredentialHelper() {
        this("git");
    }

    LocalGitCredentialHelper(String gitExecutable) {
        this.gitExecutable = gitExecutable;
    }

    GitCredentials lookup(String repositoryUri) {
        URI uri;
        try {
            uri = URI.create(repositoryUri);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }

        List<String> command = List.of(gitExecutable, "credential", "fill");
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> environment = builder.environment();
            environment.put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
        } catch (IOException e) {
            // Git is optional for this application. A missing executable simply
            // means that anonymous access will be attempted instead.
            return null;
        }

        StringBuilder input = new StringBuilder()
                .append("protocol=").append(scheme.toLowerCase()).append('\n')
                .append("host=").append(host).append('\n');

        if (uri.getPort() != -1) {
            input.append("port=").append(uri.getPort()).append('\n');
        }

        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            input.append("path=").append(path.startsWith("/") ? path.substring(1) : path).append('\n');
        }
        input.append('\n');

        try (OutputStream output = process.getOutputStream()) {
            output.write(input.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            process.destroyForcibly();
            return null;
        }

        String response;
        try (InputStream inputStream = process.getInputStream()) {
            response = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
        } catch (IOException e) {
            process.destroyForcibly();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }

        return parse(response);
    }

    private GitCredentials parse(String response) {
        String username = null;
        String password = null;

        for (String line : response.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if ("username".equals(key)) {
                username = value;
            } else if ("password".equals(key)) {
                password = value;
            }
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }
        return new GitCredentials(username, password);
    }
}
