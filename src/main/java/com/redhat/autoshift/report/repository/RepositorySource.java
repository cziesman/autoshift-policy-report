package com.redhat.autoshift.report.repository;

import java.io.IOException;
import java.nio.file.Path;

public interface RepositorySource {

    Path root() throws IOException;

    String displayName();

}
