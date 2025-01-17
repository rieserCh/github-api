package org.kohsuke.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A Github App Installation Token.
 *
 * @author Paulo Miguel Almeida
 * @see GHAppInstallation#createToken(Map) GHAppInstallation#createToken(Map)
 */
public class GHAppInstallationToken {
    private GitHub root;

    private String token;
    protected String expires_at;
    private Map<String, String> permissions;
    private List<GHRepository> repositories;
    @JsonProperty("repository_selection")
    private GHRepositorySelection repositorySelection;

    /**
     * Gets root.
     *
     * @return the root
     */
    public GitHub getRoot() {
        return root;
    }

    /**
     * Sets root.
     *
     * @param root
     *            the root
     */
    public void setRoot(GitHub root) {
        this.root = root;
    }

    /**
     * Gets permissions.
     *
     * @return the permissions
     */
    public Map<String, String> getPermissions() {
        return permissions;
    }

    /**
     * Sets permissions.
     *
     * @param permissions
     *            the permissions
     */
    public void setPermissions(Map<String, String> permissions) {
        this.permissions = permissions;
    }

    /**
     * Gets token.
     *
     * @return the token
     */
    public String getToken() {
        return token;
    }

    /**
     * Sets token.
     *
     * @param token
     *            the token
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Gets repositories.
     *
     * @return the repositories
     */
    public List<GHRepository> getRepositories() {
        return repositories;
    }

    /**
     * Sets repositories.
     *
     * @param repositories
     *            the repositories
     */
    public void setRepositories(List<GHRepository> repositories) {
        this.repositories = repositories;
    }

    /**
     * Gets repository selection.
     *
     * @return the repository selection
     */
    public GHRepositorySelection getRepositorySelection() {
        return repositorySelection;
    }

    /**
     * Sets repository selection.
     *
     * @param repositorySelection
     *            the repository selection
     */
    public void setRepositorySelection(GHRepositorySelection repositorySelection) {
        this.repositorySelection = repositorySelection;
    }

    /**
     * Gets expires at.
     *
     * @return date when this token expires
     * @throws IOException
     *             on error
     */
    @WithBridgeMethods(value = String.class, adapterMethod = "expiresAtStr")
    public Date getExpiresAt() throws IOException {
        return GitHub.parseDate(expires_at);
    }

    @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD", justification = "Bridge method of getExpiresAt")
    private Object expiresAtStr(Date id, Class type) {
        return expires_at;
    }

    GHAppInstallationToken wrapUp(GitHub root) {
        this.root = root;
        return this;
    }
}
