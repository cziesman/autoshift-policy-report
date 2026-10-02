package com.redhat.autoshift.report.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class LocalGitCredentialHelperTest {

    @Test
    void readsCredentialsFromGitCredentialHelperProtocol() throws Exception {
        Path script = Files.createTempFile("fake-git-", ".sh");
        Files.writeString(script, """
                #!/bin/sh
                cat >/dev/null
                printf 'protocol=https\\n'
                printf 'host=gitlab.example.com\\n'
                printf 'username=test-user\\n'
                printf 'password=test-token\\n'
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        GitCredentials credentials = new LocalGitCredentialHelper(script.toString())
                .lookup("https://gitlab.example.com/platform/autoshiftv2.git");

        assertThat(credentials).isNotNull();
        assertThat(credentials.username()).isEqualTo("test-user");
        assertThat(credentials.password()).isEqualTo("test-token");
    }

    @Test
    void returnsNullWhenGitExecutableIsUnavailable() {
        GitCredentials credentials = new LocalGitCredentialHelper("/does/not/exist/git")
                .lookup("https://gitlab.example.com/platform/autoshiftv2.git");

        assertThat(credentials).isNull();
    }

    @Test
    void ignoresNonHttpRepositories() {
        GitCredentials credentials = new LocalGitCredentialHelper("/does/not/exist/git")
                .lookup("ssh://git@gitlab.example.com/platform/autoshiftv2.git");

        assertThat(credentials).isNull();
    }
}
