/*
 * The MIT License
 *
 * Copyright (c) 2010, Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.kohsuke.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Arrays.*;
import static org.kohsuke.github.Previews.*;

/**
 * A repository on GitHub.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings({ "UnusedDeclaration" })
@SuppressFBWarnings(value = { "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", "UWF_UNWRITTEN_FIELD",
        "NP_UNWRITTEN_FIELD" }, justification = "JSON API")
public class GHRepository extends GHObject {
    /* package almost final */ GitHub root;

    private String description, homepage, name, full_name;
    private String html_url; // this is the UI
    /*
     * The license information makes use of the preview API.
     *
     * See: https://developer.github.com/v3/licenses/
     */
    private GHLicense license;

    private String git_url, ssh_url, clone_url, svn_url, mirror_url;
    private GHUser owner; // not fully populated. beware.
    private boolean has_issues, has_wiki, fork, has_downloads, has_pages, archived;

    private boolean allow_squash_merge;
    private boolean allow_merge_commit;
    private boolean allow_rebase_merge;

    @JsonProperty("private")
    private boolean _private;
    private int forks_count, stargazers_count, watchers_count, size, open_issues_count, subscribers_count;
    private String pushed_at;
    private Map<Integer, GHMilestone> milestones = new WeakHashMap<Integer, GHMilestone>();

    private String default_branch, language;
    private Map<String, GHCommit> commits = new WeakHashMap<String, GHCommit>();

    @SkipFromToString
    private GHRepoPermission permissions;

    private GHRepository source, parent;

    /**
     * Create deployment gh deployment builder.
     *
     * @param ref
     *            the ref
     * @return the gh deployment builder
     */
    public GHDeploymentBuilder createDeployment(String ref) {
        return new GHDeploymentBuilder(this, ref);
    }

    /**
     * Gets deployment statuses.
     *
     * @param id
     *            the id
     * @return the deployment statuses
     * @throws IOException
     *             the io exception
     * @deprecated Use {@code getDeployment(id).listStatuses()}
     */
    public PagedIterable<GHDeploymentStatus> getDeploymentStatuses(final int id) throws IOException {
        return getDeployment(id).listStatuses();
    }

    /**
     * List deployments paged iterable.
     *
     * @param sha
     *            the sha
     * @param ref
     *            the ref
     * @param task
     *            the task
     * @param environment
     *            the environment
     * @return the paged iterable
     */
    public PagedIterable<GHDeployment> listDeployments(String sha, String ref, String task, String environment) {
        List<String> params = Arrays.asList(getParam("sha", sha), getParam("ref", ref), getParam("task", task),
                getParam("environment", environment));
        final String deploymentsUrl = getApiTailUrl("deployments") + "?" + join(params, "&");
        return root.retrieve().asPagedIterable(deploymentsUrl, GHDeployment[].class,
                item -> item.wrap(GHRepository.this));
    }

    /**
     * Obtains a single {@link GHDeployment} by its ID.
     *
     * @param id
     *            the id
     * @return the deployment
     * @throws IOException
     *             the io exception
     */
    public GHDeployment getDeployment(long id) throws IOException {
        return root.retrieve().to(getApiTailUrl("deployments/" + id), GHDeployment.class).wrap(this);
    }

    private String join(List<String> params, String joinStr) {
        StringBuilder output = new StringBuilder();
        for (String param : params) {
            if (param != null) {
                output.append(param + joinStr);
            }
        }
        return output.toString();
    }

    private String getParam(String name, String value) {
        return StringUtils.trimToNull(value) == null ? null : name + "=" + value;
    }

    /**
     * Gets deploy status.
     *
     * @param deploymentId
     *            the deployment id
     * @param ghDeploymentState
     *            the gh deployment state
     * @return the deploy status
     * @throws IOException
     *             the io exception
     * @deprecated Use {@code getDeployment(deploymentId).createStatus(ghDeploymentState)}
     */
    public GHDeploymentStatusBuilder createDeployStatus(int deploymentId, GHDeploymentState ghDeploymentState)
            throws IOException {
        return getDeployment(deploymentId).createStatus(ghDeploymentState);
    }

    private static class GHRepoPermission {
        boolean pull, push, admin;
    }

    /**
     * Enum with the possible collaborator permissions. Used in
     * {@link #addCollaborators(GHCollaboratorPermission, GHUser...)} and
     * {@link #addCollaborators(GHCollaboratorPermission, Collection)}.
     *
     * Request body of the resulting request is encoded within the toString method of this enum.
     *
     * @author Christoph Rieser
     *
     * @see #addCollaborators(GHCollaboratorPermission, GHUser...)
     * @see #addCollaborators(GHCollaboratorPermission, Collection)
     * @see <a href=
     *      "https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator">https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator</a>
     **/
    public static enum GHCollaboratorPermission {
        PULL("{ \"permission\": \"pull\" }"), PUSH("{ \"permission\": \"push\" }"), ADMIN(
                "{ \"permission\": \"admin\" }");

        final private String str;

        private GHCollaboratorPermission(String str) {
            this.str = str;
        }

        @Override
        public String toString() {
            return str;
        }
    }

    /**
     * Gets description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets homepage.
     *
     * @return the homepage
     */
    public String getHomepage() {
        return homepage;
    }

    /**
     * Gets the git:// URL to this repository, such as "git://github.com/kohsuke/jenkins.git" This URL is read-only.
     *
     * @return the git transport url
     */
    public String getGitTransportUrl() {
        return git_url;
    }

    /**
     * Gets the HTTPS URL to this repository, such as "https://github.com/kohsuke/jenkins.git" This URL is read-only.
     *
     * @return the http transport url
     */
    public String getHttpTransportUrl() {
        return clone_url;
    }

    /**
     * Git http transport url string.
     *
     * @return the string
     * @deprecated Typo of {@link #getHttpTransportUrl()}
     */
    public String gitHttpTransportUrl() {
        return clone_url;
    }

    /**
     * Gets the Subversion URL to access this repository: https://github.com/rails/rails
     *
     * @return the svn url
     */
    public String getSvnUrl() {
        return svn_url;
    }

    /**
     * Gets the Mirror URL to access this repository: https://github.com/apache/tomee mirrored from
     * git://git.apache.org/tomee.git
     *
     * @return the mirror url
     */
    public String getMirrorUrl() {
        return mirror_url;
    }

    /**
     * Gets the SSH URL to access this repository, such as git@github.com:rails/rails.git
     *
     * @return the ssh url
     */
    public String getSshUrl() {
        return ssh_url;
    }

    public URL getHtmlUrl() {
        return GitHub.parseURL(html_url);
    }

    /**
     * Short repository name without the owner. For example 'jenkins' in case of http://github.com/jenkinsci/jenkins
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Full repository name including the owner or organization. For example 'jenkinsci/jenkins' in case of
     * http://github.com/jenkinsci/jenkins
     *
     * @return the full name
     */
    public String getFullName() {
        return full_name;
    }

    /**
     * Has pull access boolean.
     *
     * @return the boolean
     */
    public boolean hasPullAccess() {
        return permissions != null && permissions.pull;
    }

    /**
     * Has push access boolean.
     *
     * @return the boolean
     */
    public boolean hasPushAccess() {
        return permissions != null && permissions.push;
    }

    /**
     * Has admin access boolean.
     *
     * @return the boolean
     */
    public boolean hasAdminAccess() {
        return permissions != null && permissions.admin;
    }

    /**
     * Gets the primary programming language.
     *
     * @return the language
     */
    public String getLanguage() {
        return language;
    }

    /**
     * Gets owner.
     *
     * @return the owner
     * @throws IOException
     *             the io exception
     */
    public GHUser getOwner() throws IOException {
        return root.isOffline() ? owner : root.getUser(getOwnerName()); // because 'owner' isn't fully populated
    }

    /**
     * Gets issue.
     *
     * @param id
     *            the id
     * @return the issue
     * @throws IOException
     *             the io exception
     */
    public GHIssue getIssue(int id) throws IOException {
        return root.retrieve().to(getApiTailUrl("issues/" + id), GHIssue.class).wrap(this);
    }

    /**
     * Create issue gh issue builder.
     *
     * @param title
     *            the title
     * @return the gh issue builder
     */
    public GHIssueBuilder createIssue(String title) {
        return new GHIssueBuilder(this, title);
    }

    /**
     * Gets issues.
     *
     * @param state
     *            the state
     * @return the issues
     * @throws IOException
     *             the io exception
     */
    public List<GHIssue> getIssues(GHIssueState state) throws IOException {
        return listIssues(state).asList();
    }

    /**
     * Gets issues.
     *
     * @param state
     *            the state
     * @param milestone
     *            the milestone
     * @return the issues
     * @throws IOException
     *             the io exception
     */
    public List<GHIssue> getIssues(GHIssueState state, GHMilestone milestone) throws IOException {
        return Arrays.asList(GHIssue.wrap(root.retrieve().with("state", state)
                .with("milestone", milestone == null ? "none" : "" + milestone.getNumber())
                .to(getApiTailUrl("issues"), GHIssue[].class), this));
    }

    /**
     * Lists up all the issues in this repository.
     *
     * @param state
     *            the state
     * @return the paged iterable
     */
    public PagedIterable<GHIssue> listIssues(final GHIssueState state) {
        return root.retrieve().with("state", state).asPagedIterable(getApiTailUrl("issues"), GHIssue[].class,
                item -> item.wrap(GHRepository.this));
    }

    /**
     * Create release gh release builder.
     *
     * @param tag
     *            the tag
     * @return the gh release builder
     */
    public GHReleaseBuilder createRelease(String tag) {
        return new GHReleaseBuilder(this, tag);
    }

    /**
     * Creates a named ref, such as tag, branch, etc.
     *
     * @param name
     *            The name of the fully qualified reference (ie: refs/heads/master). If it doesn't start with 'refs' and
     *            have at least two slashes, it will be rejected.
     * @param sha
     *            The SHA1 value to set this reference to
     * @return the gh ref
     * @throws IOException
     *             the io exception
     */
    public GHRef createRef(String name, String sha) throws IOException {
        return new Requester(root).with("ref", name).with("sha", sha).method("POST")
                .to(getApiTailUrl("git/refs"), GHRef.class).wrap(root);
    }

    /**
     * Gets releases.
     *
     * @return the releases
     * @throws IOException
     *             the io exception
     * @deprecated use {@link #listReleases()}
     */
    public List<GHRelease> getReleases() throws IOException {
        return listReleases().asList();
    }

    /**
     * Gets release.
     *
     * @param id
     *            the id
     * @return the release
     * @throws IOException
     *             the io exception
     */
    public GHRelease getRelease(long id) throws IOException {
        try {
            return root.retrieve().to(getApiTailUrl("releases/" + id), GHRelease.class).wrap(this);
        } catch (FileNotFoundException e) {
            return null; // no release for this id
        }
    }

    /**
     * Gets release by tag name.
     *
     * @param tag
     *            the tag
     * @return the release by tag name
     * @throws IOException
     *             the io exception
     */
    public GHRelease getReleaseByTagName(String tag) throws IOException {
        try {
            return root.retrieve().to(getApiTailUrl("releases/tags/" + tag), GHRelease.class).wrap(this);
        } catch (FileNotFoundException e) {
            return null; // no release for this tag
        }
    }

    /**
     * Gets latest release.
     *
     * @return the latest release
     * @throws IOException
     *             the io exception
     */
    public GHRelease getLatestRelease() throws IOException {
        try {
            return root.retrieve().to(getApiTailUrl("releases/latest"), GHRelease.class).wrap(this);
        } catch (FileNotFoundException e) {
            return null; // no latest release
        }
    }

    /**
     * List releases paged iterable.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHRelease> listReleases() throws IOException {
        return root.retrieve().asPagedIterable(getApiTailUrl("releases"), GHRelease[].class,
                item -> item.wrap(GHRepository.this));
    }

    /**
     * List tags paged iterable.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHTag> listTags() throws IOException {
        return root.retrieve().asPagedIterable(getApiTailUrl("tags"), GHTag[].class,
                item -> item.wrap(GHRepository.this));
    }

    /**
     * List languages for the specified repository. The value on the right of a language is the number of bytes of code
     * written in that language. { "C": 78769, "Python": 7769 }
     *
     * @return the map
     * @throws IOException
     *             the io exception
     */
    public Map<String, Long> listLanguages() throws IOException {
        return root.retrieve().to(getApiTailUrl("languages"), HashMap.class);
    }

    /**
     * Gets owner name.
     *
     * @return the owner name
     */
    public String getOwnerName() {
        // consistency of the GitHub API is super... some serialized forms of GHRepository populate
        // a full GHUser while others populate only the owner and email. This later form is super helpful
        // in putting the login in owner.name not owner.login... thankfully we can easily identify this
        // second set because owner.login will be null
        return owner.login != null ? owner.login : owner.name;
    }

    /**
     * Has issues boolean.
     *
     * @return the boolean
     */
    public boolean hasIssues() {
        return has_issues;
    }

    /**
     * Has wiki boolean.
     *
     * @return the boolean
     */
    public boolean hasWiki() {
        return has_wiki;
    }

    /**
     * Is fork boolean.
     *
     * @return the boolean
     */
    public boolean isFork() {
        return fork;
    }

    /**
     * Is archived boolean.
     *
     * @return the boolean
     */
    public boolean isArchived() {
        return archived;
    }

    /**
     * Is allow squash merge boolean.
     *
     * @return the boolean
     */
    public boolean isAllowSquashMerge() {
        return allow_squash_merge;
    }

    /**
     * Is allow merge commit boolean.
     *
     * @return the boolean
     */
    public boolean isAllowMergeCommit() {
        return allow_merge_commit;
    }

    /**
     * Is allow rebase merge boolean.
     *
     * @return the boolean
     */
    public boolean isAllowRebaseMerge() {
        return allow_rebase_merge;
    }

    /**
     * Returns the number of all forks of this repository. This not only counts direct forks, but also forks of forks,
     * and so on.
     *
     * @return the forks
     */
    public int getForks() {
        return forks_count;
    }

    /**
     * Gets stargazers count.
     *
     * @return the stargazers count
     */
    public int getStargazersCount() {
        return stargazers_count;
    }

    /**
     * Is private boolean.
     *
     * @return the boolean
     */
    public boolean isPrivate() {
        return _private;
    }

    /**
     * Has downloads boolean.
     *
     * @return the boolean
     */
    public boolean hasDownloads() {
        return has_downloads;
    }

    /**
     * Has pages boolean.
     *
     * @return the boolean
     */
    public boolean hasPages() {
        return has_pages;
    }

    /**
     * Gets watchers.
     *
     * @return the watchers
     */
    public int getWatchers() {
        return watchers_count;
    }

    /**
     * Gets open issue count.
     *
     * @return the open issue count
     */
    public int getOpenIssueCount() {
        return open_issues_count;
    }

    /**
     * Gets network count.
     *
     * @return the network count
     * @deprecated This no longer exists in the official API documentation. Use {@link #getForks()}
     */
    public int getNetworkCount() {
        return forks_count;
    }

    /**
     * Gets subscribers count.
     *
     * @return the subscribers count
     */
    public int getSubscribersCount() {
        return subscribers_count;
    }

    /**
     * Gets pushed at.
     *
     * @return null if the repository was never pushed at.
     */
    public Date getPushedAt() {
        return GitHub.parseDate(pushed_at);
    }

    /**
     * Returns the primary branch you'll configure in the "Admin &gt; Options" config page.
     *
     * @return This field is null until the user explicitly configures the master branch.
     */
    public String getDefaultBranch() {
        return default_branch;
    }

    /**
     * Gets master branch.
     *
     * @return the master branch
     * @deprecated Renamed to {@link #getDefaultBranch()}
     */
    public String getMasterBranch() {
        return default_branch;
    }

    /**
     * Gets size.
     *
     * @return the size
     */
    public int getSize() {
        return size;
    }

    /**
     * Gets the collaborators on this repository. This set always appear to include the owner.
     *
     * @return the collaborators
     * @throws IOException
     *             the io exception
     */
    @WithBridgeMethods(Set.class)
    public GHPersonSet<GHUser> getCollaborators() throws IOException {
        return new GHPersonSet<GHUser>(listCollaborators().asList());
    }

    /**
     * Lists up the collaborators on this repository.
     *
     * @return Users paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHUser> listCollaborators() throws IOException {
        return listUsers("collaborators");
    }

    /**
     * Lists all
     * <a href="https://help.github.com/articles/assigning-issues-and-pull-requests-to-other-github-users/">the
     * available assignees</a> to which issues may be assigned.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHUser> listAssignees() throws IOException {
        return listUsers("assignees");
    }

    /**
     * Checks if the given user is an assignee for this repository.
     *
     * @param u
     *            the u
     * @return the boolean
     * @throws IOException
     *             the io exception
     */
    public boolean hasAssignee(GHUser u) throws IOException {
        return root.retrieve().asHttpStatusCode(getApiTailUrl("assignees/" + u.getLogin())) / 100 == 2;
    }

    /**
     * Gets the names of the collaborators on this repository. This method deviates from the principle of this library
     * but it works a lot faster than {@link #getCollaborators()}.
     *
     * @return the collaborator names
     * @throws IOException
     *             the io exception
     */
    public Set<String> getCollaboratorNames() throws IOException {
        Set<String> r = new HashSet<String>();
        for (GHUser u : GHUser.wrap(root.retrieve().to(getApiTailUrl("collaborators"), GHUser[].class), root))
            r.add(u.login);
        return r;
    }

    /**
     * Obtain permission for a given user in this repository.
     *
     * @param user
     *            a {@link GHUser#getLogin}
     * @return the permission
     * @throws IOException
     *             the io exception
     */
    public GHPermissionType getPermission(String user) throws IOException {
        GHPermission perm = root.retrieve().to(getApiTailUrl("collaborators/" + user + "/permission"),
                GHPermission.class);
        perm.wrapUp(root);
        return perm.getPermissionType();
    }

    /**
     * Obtain permission for a given user in this repository.
     *
     * @param u
     *            the u
     * @return the permission
     * @throws IOException
     *             the io exception
     */
    public GHPermissionType getPermission(GHUser u) throws IOException {
        return getPermission(u.getLogin());
    }

    /**
     * If this repository belongs to an organization, return a set of teams.
     *
     * @return the teams
     * @throws IOException
     *             the io exception
     */
    public Set<GHTeam> getTeams() throws IOException {
        return Collections.unmodifiableSet(new HashSet<GHTeam>(Arrays.asList(GHTeam.wrapUp(
                root.retrieve().to(getApiTailUrl("teams"), GHTeam[].class), root.getOrganization(getOwnerName())))));
    }

    /**
     * Adds GHUser(s) as collaborator(s) to this GHRepository with push permission.
     *
     * @param users
     *            user(s) to add
     * @throws IOException
     *             the io exception
     */
    public void addCollaborators(GHUser... users) throws IOException {
        addCollaborators(asList(users));
    }

    /**
     * Adds GHUser(s) as collaborator(s) to this GHRepository with push permission.
     *
     * @param users
     *            user(s) to add
     * @throws IOException
     *             the io exception
     * @see #addCollaborators(GHUser...)
     */
    public void addCollaborators(Collection<GHUser> users) throws IOException {
        modifyCollaborators(users, "PUT", null);
    }

    /**
     * Adds GHUser(s) as collaborator(s) to this GHRepository with the given permission. Only valid on organisation-owed
     * repositories!
     *
     * @param permission
     *            permission the user(s) should get.
     * @param users
     *            user(s) to add
     * @throws IOException
     *             the io exception
     * @see <a href=
     *      "https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator">https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator</a>
     */
    public void addCollaborators(GHCollaboratorPermission permission, GHUser... users) throws IOException {
        addCollaborators(permission, asList(users));
    }

    /**
     * Adds GHUser(s) as collaborator(s) to this GHRepository with the given permission. Only valid on organisation-owed
     * repositories!
     *
     * @param permission
     *            permission the user(s) should get.
     * @param users
     *            user(s) to add
     * @throws IOException
     *             the io exception
     * @see <a href=
     *      "https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator">https://developer.github.com/v3/repos/collaborators/#add-user-as-a-collaborator</a>
     * @see #addCollaborators(GHCollaboratorPermission, GHUser...)
     */
    public void addCollaborators(GHCollaboratorPermission permission, Collection<GHUser> users) throws IOException {
        modifyCollaborators(users, "PUT", permission);
    }

    /**
     * removes the given user(s) as collaborators from this repository
     * 
     * @param users
     *            user(s) to remove
     * @throws IOException
     *             the io exception
     */
    public void removeCollaborators(GHUser... users) throws IOException {
        removeCollaborators(asList(users));
    }

    /**
     * removes the given user(s) as collaborators from this repository
     *
     * @param users
     *            user(s) to remove
     * @throws IOException
     *             the io exception
     * @see #removeCollaborators(GHUser...)
     */
    public void removeCollaborators(Collection<GHUser> users) throws IOException {
        modifyCollaborators(users, "DELETE", null);
    }

    private void modifyCollaborators(Collection<GHUser> users, String method, GHCollaboratorPermission permission)
            throws IOException {
        for (GHUser user : users) {
            if ("DELETE".equals(method)) {
                new Requester(root).method(method).to(getApiTailUrl("collaborators/" + user.getLogin()));
            } else {
                Requester r = new Requester(root).method(method);
                // add request body if explicit permission is requested
                if (permission != null) {
                    r.with(new ByteArrayInputStream(permission.toString().getBytes(Charset.forName("UTF-8"))));
                    r.contentType("application/json;charset=UTF-8");
                }
                r.to(getApiTailUrl("collaborators/" + user.getLogin()));
            }
        }
    }

    /**
     * Sets email service hook.
     *
     * @param address
     *            the address
     * @throws IOException
     *             the io exception
     */
    public void setEmailServiceHook(String address) throws IOException {
        Map<String, String> config = new HashMap<String, String>();
        config.put("address", address);
        new Requester(root).method("POST").with("name", "email").with("config", config).with("active", true)
                .to(getApiTailUrl("hooks"));
    }

    private void edit(String key, String value) throws IOException {
        Requester requester = new Requester(root);
        if (!key.equals("name"))
            requester.with("name", name); // even when we don't change the name, we need to send it in
        requester.with(key, value).method("PATCH").to(getApiTailUrl(""));
    }

    /**
     * Enables or disables the issue tracker for this repository.
     *
     * @param v
     *            the v
     * @throws IOException
     *             the io exception
     */
    public void enableIssueTracker(boolean v) throws IOException {
        edit("has_issues", String.valueOf(v));
    }

    /**
     * Enables or disables Wiki for this repository.
     *
     * @param v
     *            the v
     * @throws IOException
     *             the io exception
     */
    public void enableWiki(boolean v) throws IOException {
        edit("has_wiki", String.valueOf(v));
    }

    /**
     * Enable downloads.
     *
     * @param v
     *            the v
     * @throws IOException
     *             the io exception
     */
    public void enableDownloads(boolean v) throws IOException {
        edit("has_downloads", String.valueOf(v));
    }

    /**
     * Rename this repository.
     *
     * @param name
     *            the name
     * @throws IOException
     *             the io exception
     */
    public void renameTo(String name) throws IOException {
        edit("name", name);
    }

    /**
     * Sets description.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void setDescription(String value) throws IOException {
        edit("description", value);
    }

    /**
     * Sets homepage.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void setHomepage(String value) throws IOException {
        edit("homepage", value);
    }

    /**
     * Sets default branch.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void setDefaultBranch(String value) throws IOException {
        edit("default_branch", value);
    }

    /**
     * Sets private.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void setPrivate(boolean value) throws IOException {
        edit("private", Boolean.toString(value));
    }

    /**
     * Allow squash merge.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void allowSquashMerge(boolean value) throws IOException {
        edit("allow_squash_merge", Boolean.toString(value));
    }

    /**
     * Allow merge commit.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void allowMergeCommit(boolean value) throws IOException {
        edit("allow_merge_commit", Boolean.toString(value));
    }

    /**
     * Allow rebase merge.
     *
     * @param value
     *            the value
     * @throws IOException
     *             the io exception
     */
    public void allowRebaseMerge(boolean value) throws IOException {
        edit("allow_rebase_merge", Boolean.toString(value));
    }

    /**
     * Deletes this repository.
     *
     * @throws IOException
     *             the io exception
     */
    public void delete() throws IOException {
        try {
            new Requester(root).method("DELETE").to(getApiTailUrl(""));
        } catch (FileNotFoundException x) {
            throw (FileNotFoundException) new FileNotFoundException("Failed to delete " + getOwnerName() + "/" + name
                    + "; might not exist, or you might need the delete_repo scope in your token: http://stackoverflow.com/a/19327004/12916")
                            .initCause(x);
        }
    }

    /**
     * Will archive and this repository as read-only. When a repository is archived, any operation that can change its
     * state is forbidden. This applies symmetrically if trying to unarchive it.
     *
     * <p>
     * When you try to do any operation that modifies a read-only repository, it returns the response:
     *
     * <pre>
     * org.kohsuke.github.HttpException: {
     *     "message":"Repository was archived so is read-only.",
     *     "documentation_url":"https://developer.github.com/v3/repos/#edit"
     * }
     * </pre>
     *
     * @throws IOException
     *             In case of any networking error or error from the server.
     */
    public void archive() throws IOException {
        edit("archived", "true");
        // Generall would not update this record,
        // but do so here since this will result in any other update actions failing
        archived = true;
    }

    /**
     * Sort orders for listing forks
     */
    public enum ForkSort {
        NEWEST, OLDEST, STARGAZERS
    }

    /**
     * Lists all the direct forks of this repository, sorted by github api default, currently {@link ForkSort#NEWEST
     * ForkSort.NEWEST}*.
     *
     * @return the paged iterable
     */
    public PagedIterable<GHRepository> listForks() {
        return listForks(null);
    }

    /**
     * Lists all the direct forks of this repository, sorted by the given sort order.
     *
     * @param sort
     *            the sort order. If null, defaults to github api default, currently {@link ForkSort#NEWEST
     *            ForkSort.NEWEST}.
     * @return the paged iterable
     */
    public PagedIterable<GHRepository> listForks(final ForkSort sort) {
        return root.retrieve().with("sort", sort).asPagedIterable(getApiTailUrl("forks"), GHRepository[].class,
                item -> item.wrap(root));
    }

    /**
     * Forks this repository as your repository.
     *
     * @return Newly forked repository that belong to you.
     * @throws IOException
     *             the io exception
     */
    public GHRepository fork() throws IOException {
        new Requester(root).method("POST").to(getApiTailUrl("forks"), null);

        // this API is asynchronous. we need to wait for a bit
        for (int i = 0; i < 10; i++) {
            GHRepository r = root.getMyself().getRepository(name);
            if (r != null)
                return r;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
        }
        throw new IOException(this + " was forked but can't find the new repository");
    }

    /**
     * Forks this repository into an organization.
     *
     * @param org
     *            the org
     * @return Newly forked repository that belong to you.
     * @throws IOException
     *             the io exception
     */
    public GHRepository forkTo(GHOrganization org) throws IOException {
        new Requester(root).to(getApiTailUrl("forks?org=" + org.getLogin()));

        // this API is asynchronous. we need to wait for a bit
        for (int i = 0; i < 10; i++) {
            GHRepository r = org.getRepository(name);
            if (r != null)
                return r;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
        }
        throw new IOException(this + " was forked into " + org.getLogin() + " but can't find the new repository");
    }

    /**
     * Retrieves a specified pull request.
     *
     * @param i
     *            the
     * @return the pull request
     * @throws IOException
     *             the io exception
     */
    public GHPullRequest getPullRequest(int i) throws IOException {
        return root.retrieve().withPreview(SHADOW_CAT).to(getApiTailUrl("pulls/" + i), GHPullRequest.class)
                .wrapUp(this);
    }

    /**
     * Retrieves all the pull requests of a particular state.
     *
     * @param state
     *            the state
     * @return the pull requests
     * @throws IOException
     *             the io exception
     * @see #listPullRequests(GHIssueState) #listPullRequests(GHIssueState)
     */
    public List<GHPullRequest> getPullRequests(GHIssueState state) throws IOException {
        return queryPullRequests().state(state).list().asList();
    }

    /**
     * Retrieves all the pull requests of a particular state.
     *
     * @param state
     *            the state
     * @return the paged iterable
     * @deprecated Use {@link #queryPullRequests()}
     */
    public PagedIterable<GHPullRequest> listPullRequests(GHIssueState state) {
        return queryPullRequests().state(state).list();
    }

    /**
     * Retrieves pull requests.
     *
     * @return the gh pull request query builder
     */
    public GHPullRequestQueryBuilder queryPullRequests() {
        return new GHPullRequestQueryBuilder(this);
    }

    /**
     * Creates a new pull request.
     *
     * @param title
     *            Required. The title of the pull request.
     * @param head
     *            Required. The name of the branch where your changes are implemented. For cross-repository pull
     *            requests in the same network, namespace head with a user like this: username:branch.
     * @param base
     *            Required. The name of the branch you want your changes pulled into. This should be an existing branch
     *            on the current repository.
     * @param body
     *            The contents of the pull request. This is the markdown description of a pull request.
     * @return the gh pull request
     * @throws IOException
     *             the io exception
     */
    public GHPullRequest createPullRequest(String title, String head, String base, String body) throws IOException {
        return createPullRequest(title, head, base, body, true);
    }

    /**
     * Creates a new pull request. Maintainer's permissions aware.
     *
     * @param title
     *            Required. The title of the pull request.
     * @param head
     *            Required. The name of the branch where your changes are implemented. For cross-repository pull
     *            requests in the same network, namespace head with a user like this: username:branch.
     * @param base
     *            Required. The name of the branch you want your changes pulled into. This should be an existing branch
     *            on the current repository.
     * @param body
     *            The contents of the pull request. This is the markdown description of a pull request.
     * @param maintainerCanModify
     *            Indicates whether maintainers can modify the pull request.
     * @return the gh pull request
     * @throws IOException
     *             the io exception
     */
    public GHPullRequest createPullRequest(String title, String head, String base, String body,
            boolean maintainerCanModify) throws IOException {
        return createPullRequest(title, head, base, body, maintainerCanModify, false);
    }

    /**
     * Creates a new pull request. Maintainer's permissions and draft aware.
     *
     * @param title
     *            Required. The title of the pull request.
     * @param head
     *            Required. The name of the branch where your changes are implemented. For cross-repository pull
     *            requests in the same network, namespace head with a user like this: username:branch.
     * @param base
     *            Required. The name of the branch you want your changes pulled into. This should be an existing branch
     *            on the current repository.
     * @param body
     *            The contents of the pull request. This is the markdown description of a pull request.
     * @param maintainerCanModify
     *            Indicates whether maintainers can modify the pull request.
     * @param draft
     *            Indicates whether to create a draft pull request or not.
     * @return the gh pull request
     * @throws IOException
     *             the io exception
     */
    public GHPullRequest createPullRequest(String title, String head, String base, String body,
            boolean maintainerCanModify, boolean draft) throws IOException {
        return new Requester(root).withPreview(SHADOW_CAT).with("title", title).with("head", head).with("base", base)
                .with("body", body).with("maintainer_can_modify", maintainerCanModify).with("draft", draft)
                .to(getApiTailUrl("pulls"), GHPullRequest.class).wrapUp(this);
    }

    /**
     * Retrieves the currently configured hooks.
     *
     * @return the hooks
     * @throws IOException
     *             the io exception
     */
    public List<GHHook> getHooks() throws IOException {
        return GHHooks.repoContext(this, owner).getHooks();
    }

    /**
     * Gets hook.
     *
     * @param id
     *            the id
     * @return the hook
     * @throws IOException
     *             the io exception
     */
    public GHHook getHook(int id) throws IOException {
        return GHHooks.repoContext(this, owner).getHook(id);
    }

    /**
     * Gets a comparison between 2 points in the repository. This would be similar to calling
     * <code>git log id1...id2</code> against a local repository.
     *
     * @param id1
     *            an identifier for the first point to compare from, this can be a sha1 ID (for a commit, tag etc) or a
     *            direct tag name
     * @param id2
     *            an identifier for the second point to compare to. Can be the same as the first point.
     * @return the comparison output
     * @throws IOException
     *             on failure communicating with GitHub
     */
    public GHCompare getCompare(String id1, String id2) throws IOException {
        GHCompare compare = root.retrieve().to(getApiTailUrl(String.format("compare/%s...%s", id1, id2)),
                GHCompare.class);
        return compare.wrap(this);
    }

    /**
     * Gets compare.
     *
     * @param id1
     *            the id 1
     * @param id2
     *            the id 2
     * @return the compare
     * @throws IOException
     *             the io exception
     */
    public GHCompare getCompare(GHCommit id1, GHCommit id2) throws IOException {
        return getCompare(id1.getSHA1(), id2.getSHA1());
    }

    /**
     * Gets compare.
     *
     * @param id1
     *            the id 1
     * @param id2
     *            the id 2
     * @return the compare
     * @throws IOException
     *             the io exception
     */
    public GHCompare getCompare(GHBranch id1, GHBranch id2) throws IOException {

        GHRepository owner1 = id1.getOwner();
        GHRepository owner2 = id2.getOwner();

        // If the owner of the branches is different, we have a cross-fork compare.
        if (owner1 != null && owner2 != null) {
            String ownerName1 = owner1.getOwnerName();
            String ownerName2 = owner2.getOwnerName();
            if (!StringUtils.equals(ownerName1, ownerName2)) {
                String qualifiedName1 = String.format("%s:%s", ownerName1, id1.getName());
                String qualifiedName2 = String.format("%s:%s", ownerName2, id2.getName());
                return getCompare(qualifiedName1, qualifiedName2);
            }
        }

        return getCompare(id1.getName(), id2.getName());

    }

    /**
     * Retrieves all refs for the github repository.
     *
     * @return an array of GHRef elements coresponding with the refs in the remote repository.
     * @throws IOException
     *             on failure communicating with GitHub
     */
    public GHRef[] getRefs() throws IOException {
        return GHRef.wrap(
                root.retrieve().to(String.format("/repos/%s/%s/git/refs", getOwnerName(), name), GHRef[].class), root);
    }

    /**
     * Retrieves all refs for the github repository.
     *
     * @return paged iterable of all refs
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid ref type being requested
     */
    public PagedIterable<GHRef> listRefs() throws IOException {
        final String url = String.format("/repos/%s/%s/git/refs", getOwnerName(), name);
        return root.retrieve().asPagedIterable(url, GHRef[].class, item -> item.wrap(root));
    }

    /**
     * Retrieves all refs of the given type for the current GitHub repository.
     *
     * @param refType
     *            the type of reg to search for e.g. <code>tags</code> or <code>commits</code>
     * @return an array of all refs matching the request type
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid ref type being requested
     */
    public GHRef[] getRefs(String refType) throws IOException {
        return GHRef.wrap(root.retrieve().to(String.format("/repos/%s/%s/git/refs/%s", getOwnerName(), name, refType),
                GHRef[].class), root);
    }

    /**
     * Retrieves all refs of the given type for the current GitHub repository.
     *
     * @param refType
     *            the type of reg to search for e.g. <code>tags</code> or <code>commits</code>
     * @return paged iterable of all refs of the specified type
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid ref type being requested
     */
    public PagedIterable<GHRef> listRefs(String refType) throws IOException {
        final String url = String.format("/repos/%s/%s/git/refs/%s", getOwnerName(), name, refType);
        return root.retrieve().asPagedIterable(url, GHRef[].class, item -> item.wrap(root));
    }

    /**
     * Retrive a ref of the given type for the current GitHub repository.
     *
     * @param refName
     *            eg: heads/branch
     * @return refs matching the request type
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid ref type being requested
     */
    public GHRef getRef(String refName) throws IOException {
        // hashes in branch names must be replaced with the url encoded equivalent or this call will fail
        // FIXME: how about other URL unsafe characters, like space, @, : etc? do we need to be using
        // URLEncoder.encode()?
        // OTOH, '/' need no escaping
        refName = refName.replaceAll("#", "%23");
        return root.retrieve().to(String.format("/repos/%s/%s/git/refs/%s", getOwnerName(), name, refName), GHRef.class)
                .wrap(root);
    }

    /**
     * Returns the <strong>annotated</strong> tag object. Only valid if the {@link GHRef#getObject()} has a
     * {@link GHRef.GHObject#getType()} of {@code tag}.
     *
     * @param sha
     *            the sha of the tag object
     * @return the annotated tag object
     * @throws IOException
     *             the io exception
     */
    public GHTagObject getTagObject(String sha) throws IOException {
        return root.retrieve().to(getApiTailUrl("git/tags/" + sha), GHTagObject.class).wrap(this);
    }

    /**
     * Retrive a tree of the given type for the current GitHub repository.
     *
     * @param sha
     *            sha number or branch name ex: "master"
     * @return refs matching the request type
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid tree type being requested
     */
    public GHTree getTree(String sha) throws IOException {
        String url = String.format("/repos/%s/%s/git/trees/%s", getOwnerName(), name, sha);
        return root.retrieve().to(url, GHTree.class).wrap(this);
    }

    /**
     * Create tree gh tree builder.
     *
     * @return the gh tree builder
     */
    public GHTreeBuilder createTree() {
        return new GHTreeBuilder(this);
    }

    /**
     * Retrieves the tree for the current GitHub repository, recursively as described in here:
     * https://developer.github.com/v3/git/trees/#get-a-tree-recursively
     *
     * @param sha
     *            sha number or branch name ex: "master"
     * @param recursive
     *            use 1
     * @return the tree recursive
     * @throws IOException
     *             on failure communicating with GitHub, potentially due to an invalid tree type being requested
     */
    public GHTree getTreeRecursive(String sha, int recursive) throws IOException {
        String url = String.format("/repos/%s/%s/git/trees/%s?recursive=%d", getOwnerName(), name, sha, recursive);
        return root.retrieve().to(url, GHTree.class).wrap(this);
    }

    /**
     * Obtains the metadata &amp; the content of a blob.
     *
     * <p>
     * This method retrieves the whole content in memory, so beware when you are dealing with large BLOB.
     *
     * @param blobSha
     *            the blob sha
     * @return the blob
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/git/blobs/#get-a-blob">Get a blob</a>
     * @see #readBlob(String) #readBlob(String)
     */
    public GHBlob getBlob(String blobSha) throws IOException {
        String target = getApiTailUrl("git/blobs/" + blobSha);
        return root.retrieve().to(target, GHBlob.class);
    }

    /**
     * Create blob gh blob builder.
     *
     * @return the gh blob builder
     */
    public GHBlobBuilder createBlob() {
        return new GHBlobBuilder(this);
    }

    /**
     * Reads the content of a blob as a stream for better efficiency.
     *
     * @param blobSha
     *            the blob sha
     * @return the input stream
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/git/blobs/#get-a-blob">Get a blob</a>
     * @see #getBlob(String) #getBlob(String)
     */
    public InputStream readBlob(String blobSha) throws IOException {
        String target = getApiTailUrl("git/blobs/" + blobSha);
        return root.retrieve().withHeader("Accept", "application/vnd.github.VERSION.raw").asStream(target);
    }

    /**
     * Gets a commit object in this repository.
     *
     * @param sha1
     *            the sha 1
     * @return the commit
     * @throws IOException
     *             the io exception
     */
    public GHCommit getCommit(String sha1) throws IOException {
        GHCommit c = commits.get(sha1);
        if (c == null) {
            c = root.retrieve().to(String.format("/repos/%s/%s/commits/%s", getOwnerName(), name, sha1), GHCommit.class)
                    .wrapUp(this);
            commits.put(sha1, c);
        }
        return c;
    }

    /**
     * Create commit gh commit builder.
     *
     * @return the gh commit builder
     */
    public GHCommitBuilder createCommit() {
        return new GHCommitBuilder(this);
    }

    /**
     * Lists all the commits.
     *
     * @return the paged iterable
     */
    public PagedIterable<GHCommit> listCommits() {
        return root.retrieve().asPagedIterable(String.format("/repos/%s/%s/commits", getOwnerName(), name),
                GHCommit[].class, item -> item.wrapUp(GHRepository.this));
    }

    /**
     * Search commits by specifying filters through a builder pattern.
     *
     * @return the gh commit query builder
     */
    public GHCommitQueryBuilder queryCommits() {
        return new GHCommitQueryBuilder(this);
    }

    /**
     * Lists up all the commit comments in this repository.
     *
     * @return the paged iterable
     */
    public PagedIterable<GHCommitComment> listCommitComments() {
        return root.retrieve().asPagedIterable(String.format("/repos/%s/%s/comments", getOwnerName(), name),
                GHCommitComment[].class, item -> item.wrap(GHRepository.this));
    }

    /**
     * Gets the basic license details for the repository.
     * <p>
     *
     * @return null if there's no license.
     * @throws IOException
     *             as usual but also if you don't use the preview connector
     */
    public GHLicense getLicense() throws IOException {
        GHContentWithLicense lic = getLicenseContent_();
        return lic != null ? lic.license : null;
    }

    /**
     * Retrieves the contents of the repository's license file - makes an additional API call
     * <p>
     *
     * @return details regarding the license contents, or null if there's no license.
     * @throws IOException
     *             as usual but also if you don't use the preview connector
     */
    public GHContent getLicenseContent() throws IOException {
        return getLicenseContent_();
    }

    private GHContentWithLicense getLicenseContent_() throws IOException {
        try {
            return root.retrieve().to(getApiTailUrl("license"), GHContentWithLicense.class).wrap(this);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * /** Lists all the commit statues attached to the given commit, newer ones first.
     *
     * @param sha1
     *            the sha 1
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHCommitStatus> listCommitStatuses(final String sha1) throws IOException {
        return root.retrieve().asPagedIterable(String.format("/repos/%s/%s/statuses/%s", getOwnerName(), name, sha1),
                GHCommitStatus[].class, item -> item.wrapUp(root));
    }

    /**
     * Gets the last status of this commit, which is what gets shown in the UI.
     *
     * @param sha1
     *            the sha 1
     * @return the last commit status
     * @throws IOException
     *             the io exception
     */
    public GHCommitStatus getLastCommitStatus(String sha1) throws IOException {
        List<GHCommitStatus> v = listCommitStatuses(sha1).asList();
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * Creates a commit status
     *
     * @param sha1
     *            the sha 1
     * @param state
     *            the state
     * @param targetUrl
     *            Optional parameter that points to the URL that has more details.
     * @param description
     *            Optional short description.
     * @param context
     *            Optinal commit status context.
     * @return the gh commit status
     * @throws IOException
     *             the io exception
     */
    public GHCommitStatus createCommitStatus(String sha1, GHCommitState state, String targetUrl, String description,
            String context) throws IOException {
        return new Requester(root).with("state", state).with("target_url", targetUrl).with("description", description)
                .with("context", context)
                .to(String.format("/repos/%s/%s/statuses/%s", getOwnerName(), this.name, sha1), GHCommitStatus.class)
                .wrapUp(root);
    }

    /**
     * Create commit status gh commit status.
     *
     * @param sha1
     *            the sha 1
     * @param state
     *            the state
     * @param targetUrl
     *            the target url
     * @param description
     *            the description
     * @return the gh commit status
     * @throws IOException
     *             the io exception
     * @see #createCommitStatus(String, GHCommitState, String, String, String) #createCommitStatus(String,
     *      GHCommitState,String,String,String)
     */
    public GHCommitStatus createCommitStatus(String sha1, GHCommitState state, String targetUrl, String description)
            throws IOException {
        return createCommitStatus(sha1, state, targetUrl, description, null);
    }

    /**
     * Lists repository events.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHEventInfo> listEvents() throws IOException {
        return root.retrieve().asPagedIterable(String.format("/repos/%s/%s/events", getOwnerName(), name),
                GHEventInfo[].class, item -> item.wrapUp(root));
    }

    /**
     * Lists labels in this repository.
     * <p>
     * https://developer.github.com/v3/issues/labels/#list-all-labels-for-this-repository
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHLabel> listLabels() throws IOException {
        return root.retrieve().withPreview(SYMMETRA).asPagedIterable(getApiTailUrl("labels"), GHLabel[].class,
                item -> item.wrapUp(GHRepository.this));
    }

    /**
     * Gets label.
     *
     * @param name
     *            the name
     * @return the label
     * @throws IOException
     *             the io exception
     */
    public GHLabel getLabel(String name) throws IOException {
        return root.retrieve().withPreview(SYMMETRA).to(getApiTailUrl("labels/" + name), GHLabel.class).wrapUp(this);
    }

    /**
     * Create label gh label.
     *
     * @param name
     *            the name
     * @param color
     *            the color
     * @return the gh label
     * @throws IOException
     *             the io exception
     */
    public GHLabel createLabel(String name, String color) throws IOException {
        return createLabel(name, color, "");
    }

    /**
     * Description is still in preview.
     *
     * @param name
     *            the name
     * @param color
     *            the color
     * @param description
     *            the description
     * @return gh label
     * @throws IOException
     *             the io exception
     */
    @Preview
    @Deprecated
    public GHLabel createLabel(String name, String color, String description) throws IOException {
        return root.retrieve().method("POST").withPreview(SYMMETRA).with("name", name).with("color", color)
                .with("description", description).to(getApiTailUrl("labels"), GHLabel.class).wrapUp(this);
    }

    /**
     * Lists all the invitations.
     *
     * @return the paged iterable
     */
    public PagedIterable<GHInvitation> listInvitations() {
        return root.retrieve().asPagedIterable(String.format("/repos/%s/%s/invitations", getOwnerName(), name),
                GHInvitation[].class, item -> item.wrapUp(root));
    }

    /**
     * Lists all the subscribers (aka watchers.)
     * <p>
     * https://developer.github.com/v3/activity/watching/
     *
     * @return the paged iterable
     */
    public PagedIterable<GHUser> listSubscribers() {
        return listUsers("subscribers");
    }

    /**
     * Lists all the users who have starred this repo based on the old version of the API. For additional information,
     * like date when the repository was starred, see {@link #listStargazers2()}
     *
     * @return the paged iterable
     */
    public PagedIterable<GHUser> listStargazers() {
        return listUsers("stargazers");
    }

    /**
     * Lists all the users who have starred this repo based on new version of the API, having extended information like
     * the time when the repository was starred. For compatibility with the old API see {@link #listStargazers()}
     *
     * @return the paged iterable
     */
    public PagedIterable<GHStargazer> listStargazers2() {
        return root.retrieve().withPreview("application/vnd.github.v3.star+json").asPagedIterable(
                getApiTailUrl("stargazers"), GHStargazer[].class, item -> item.wrapUp(GHRepository.this));
    }

    private PagedIterable<GHUser> listUsers(final String suffix) {
        return root.retrieve().asPagedIterable(getApiTailUrl(suffix), GHUser[].class, item -> item.wrapUp(root));
    }

    /**
     * See https://api.github.com/hooks for possible names and their configuration scheme. TODO: produce type-safe
     * binding
     *
     * @param name
     *            Type of the hook to be created. See https://api.github.com/hooks for possible names.
     * @param config
     *            The configuration hash.
     * @param events
     *            Can be null. Types of events to hook into.
     * @param active
     *            the active
     * @return the gh hook
     * @throws IOException
     *             the io exception
     */
    public GHHook createHook(String name, Map<String, String> config, Collection<GHEvent> events, boolean active)
            throws IOException {
        return GHHooks.repoContext(this, owner).createHook(name, config, events, active);
    }

    /**
     * Create web hook gh hook.
     *
     * @param url
     *            the url
     * @param events
     *            the events
     * @return the gh hook
     * @throws IOException
     *             the io exception
     */
    public GHHook createWebHook(URL url, Collection<GHEvent> events) throws IOException {
        return createHook("web", Collections.singletonMap("url", url.toExternalForm()), events, true);
    }

    /**
     * Create web hook gh hook.
     *
     * @param url
     *            the url
     * @return the gh hook
     * @throws IOException
     *             the io exception
     */
    public GHHook createWebHook(URL url) throws IOException {
        return createWebHook(url, null);
    }

    // this is no different from getPullRequests(OPEN)
    // /**
    // * Retrieves all the pull requests.
    // */
    // public List<GHPullRequest> getPullRequests() throws IOException {
    // return root.retrieveWithAuth("/pulls/"+owner+'/'+name,JsonPullRequests.class).wrap(root);
    // }

    /**
     * Returns a set that represents the post-commit hook URLs. The returned set is live, and changes made to them are
     * reflected to GitHub.
     *
     * @return the post commit hooks
     * @deprecated Use {@link #getHooks()} and {@link #createHook(String, Map, Collection, boolean)}
     */
    @SuppressFBWarnings(value = "DMI_COLLECTION_OF_URLS", justification = "It causes a performance degradation, but we have already exposed it to the API")
    public Set<URL> getPostCommitHooks() {
        return postCommitHooks;
    }

    /**
     * Live set view of the post-commit hook.
     */
    @SuppressFBWarnings(value = "DMI_COLLECTION_OF_URLS", justification = "It causes a performance degradation, but we have already exposed it to the API")
    @SkipFromToString
    private final Set<URL> postCommitHooks = new AbstractSet<URL>() {
        private List<URL> getPostCommitHooks() {
            try {
                List<URL> r = new ArrayList<URL>();
                for (GHHook h : getHooks()) {
                    if (h.getName().equals("web")) {
                        r.add(new URL(h.getConfig().get("url")));
                    }
                }
                return r;
            } catch (IOException e) {
                throw new GHException("Failed to retrieve post-commit hooks", e);
            }
        }

        @Override
        public Iterator<URL> iterator() {
            return getPostCommitHooks().iterator();
        }

        @Override
        public int size() {
            return getPostCommitHooks().size();
        }

        @Override
        public boolean add(URL url) {
            try {
                createWebHook(url);
                return true;
            } catch (IOException e) {
                throw new GHException("Failed to update post-commit hooks", e);
            }
        }

        @Override
        public boolean remove(Object url) {
            try {
                String _url = ((URL) url).toExternalForm();
                for (GHHook h : getHooks()) {
                    if (h.getName().equals("web") && h.getConfig().get("url").equals(_url)) {
                        h.delete();
                        return true;
                    }
                }
                return false;
            } catch (IOException e) {
                throw new GHException("Failed to update post-commit hooks", e);
            }
        }
    };

    GHRepository wrap(GitHub root) {
        this.root = root;
        if (root.isOffline()) {
            owner.wrapUp(root);
        }
        return this;
    }

    /**
     * Gets branches by {@linkplain GHBranch#getName() their names}.
     *
     * @return the branches
     * @throws IOException
     *             the io exception
     */
    public Map<String, GHBranch> getBranches() throws IOException {
        Map<String, GHBranch> r = new TreeMap<String, GHBranch>();
        for (GHBranch p : root.retrieve().to(getApiTailUrl("branches"), GHBranch[].class)) {
            p.wrap(this);
            r.put(p.getName(), p);
        }
        return r;
    }

    /**
     * Replace special characters (e.g. #) with standard values (e.g. %23) so GitHub understands what is being
     * requested.
     *
     * @param value
     *            string to be encoded.
     * @return The encoded string.
     */
    private String UrlEncode(String value) {
        try {
            return URLEncoder.encode(value, org.apache.commons.codec.CharEncoding.UTF_8);
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(GHRepository.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Something went wrong - just return original value as is.
        return value;
    }

    /**
     * Gets branch.
     *
     * @param name
     *            the name
     * @return the branch
     * @throws IOException
     *             the io exception
     */
    public GHBranch getBranch(String name) throws IOException {
        return root.retrieve().to(getApiTailUrl("branches/" + UrlEncode(name)), GHBranch.class).wrap(this);
    }

    /**
     * Gets milestones.
     *
     * @return the milestones
     * @throws IOException
     *             the io exception
     * @deprecated Use {@link #listMilestones(GHIssueState)}
     */
    public Map<Integer, GHMilestone> getMilestones() throws IOException {
        Map<Integer, GHMilestone> milestones = new TreeMap<Integer, GHMilestone>();
        for (GHMilestone m : listMilestones(GHIssueState.OPEN)) {
            milestones.put(m.getNumber(), m);
        }
        return milestones;
    }

    /**
     * Lists up all the milestones in this repository.
     *
     * @param state
     *            the state
     * @return the paged iterable
     */
    public PagedIterable<GHMilestone> listMilestones(final GHIssueState state) {
        return root.retrieve().with("state", state).asPagedIterable(getApiTailUrl("milestones"), GHMilestone[].class,
                item -> item.wrap(GHRepository.this));
    }

    /**
     * Gets milestone.
     *
     * @param number
     *            the number
     * @return the milestone
     * @throws IOException
     *             the io exception
     */
    public GHMilestone getMilestone(int number) throws IOException {
        GHMilestone m = milestones.get(number);
        if (m == null) {
            m = root.retrieve().to(getApiTailUrl("milestones/" + number), GHMilestone.class);
            m.owner = this;
            m.root = root;
            milestones.put(m.getNumber(), m);
        }
        return m;
    }

    /**
     * Gets file content.
     *
     * @param path
     *            the path
     * @return the file content
     * @throws IOException
     *             the io exception
     */
    public GHContent getFileContent(String path) throws IOException {
        return getFileContent(path, null);
    }

    /**
     * Gets file content.
     *
     * @param path
     *            the path
     * @param ref
     *            the ref
     * @return the file content
     * @throws IOException
     *             the io exception
     */
    public GHContent getFileContent(String path, String ref) throws IOException {
        Requester requester = root.retrieve();
        String target = getApiTailUrl("contents/" + path);

        return requester.with("ref", ref).to(target, GHContent.class).wrap(this);
    }

    /**
     * Gets directory content.
     *
     * @param path
     *            the path
     * @return the directory content
     * @throws IOException
     *             the io exception
     */
    public List<GHContent> getDirectoryContent(String path) throws IOException {
        return getDirectoryContent(path, null);
    }

    /**
     * Gets directory content.
     *
     * @param path
     *            the path
     * @param ref
     *            the ref
     * @return the directory content
     * @throws IOException
     *             the io exception
     */
    public List<GHContent> getDirectoryContent(String path, String ref) throws IOException {
        Requester requester = root.retrieve();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String target = getApiTailUrl("contents/" + path);

        GHContent[] files = requester.with("ref", ref).to(target, GHContent[].class);

        GHContent.wrap(files, this);

        return Arrays.asList(files);
    }

    /**
     * https://developer.github.com/v3/repos/contents/#get-the-readme
     *
     * @return the readme
     * @throws IOException
     *             the io exception
     */
    public GHContent getReadme() throws IOException {
        Requester requester = root.retrieve();
        return requester.to(getApiTailUrl("readme"), GHContent.class).wrap(this);
    }

    /**
     * Creates a new content, or update an existing content.
     *
     * @return the gh content builder
     */
    public GHContentBuilder createContent() {
        return new GHContentBuilder(this);
    }

    /**
     * Use {@link #createContent()}.
     *
     * @param content
     *            the content
     * @param commitMessage
     *            the commit message
     * @param path
     *            the path
     * @return the gh content update response
     * @throws IOException
     *             the io exception
     */
    @Deprecated
    public GHContentUpdateResponse createContent(String content, String commitMessage, String path) throws IOException {
        return createContent().content(content).message(commitMessage).path(path).commit();
    }

    /**
     * Use {@link #createContent()}.
     *
     * @param content
     *            the content
     * @param commitMessage
     *            the commit message
     * @param path
     *            the path
     * @param branch
     *            the branch
     * @return the gh content update response
     * @throws IOException
     *             the io exception
     */
    @Deprecated
    public GHContentUpdateResponse createContent(String content, String commitMessage, String path, String branch)
            throws IOException {
        return createContent().content(content).message(commitMessage).path(path).branch(branch).commit();
    }

    /**
     * Use {@link #createContent()}.
     *
     * @param contentBytes
     *            the content bytes
     * @param commitMessage
     *            the commit message
     * @param path
     *            the path
     * @return the gh content update response
     * @throws IOException
     *             the io exception
     */
    @Deprecated
    public GHContentUpdateResponse createContent(byte[] contentBytes, String commitMessage, String path)
            throws IOException {
        return createContent().content(contentBytes).message(commitMessage).path(path).commit();
    }

    /**
     * Use {@link #createContent()}.
     *
     * @param contentBytes
     *            the content bytes
     * @param commitMessage
     *            the commit message
     * @param path
     *            the path
     * @param branch
     *            the branch
     * @return the gh content update response
     * @throws IOException
     *             the io exception
     */
    @Deprecated
    public GHContentUpdateResponse createContent(byte[] contentBytes, String commitMessage, String path, String branch)
            throws IOException {
        return createContent().content(contentBytes).message(commitMessage).path(path).branch(branch).commit();
    }

    /**
     * Create milestone gh milestone.
     *
     * @param title
     *            the title
     * @param description
     *            the description
     * @return the gh milestone
     * @throws IOException
     *             the io exception
     */
    public GHMilestone createMilestone(String title, String description) throws IOException {
        return new Requester(root).with("title", title).with("description", description).method("POST")
                .to(getApiTailUrl("milestones"), GHMilestone.class).wrap(this);
    }

    /**
     * Add deploy key gh deploy key.
     *
     * @param title
     *            the title
     * @param key
     *            the key
     * @return the gh deploy key
     * @throws IOException
     *             the io exception
     */
    public GHDeployKey addDeployKey(String title, String key) throws IOException {
        return new Requester(root).with("title", title).with("key", key).method("POST")
                .to(getApiTailUrl("keys"), GHDeployKey.class).wrap(this);

    }

    /**
     * Gets deploy keys.
     *
     * @return the deploy keys
     * @throws IOException
     *             the io exception
     */
    public List<GHDeployKey> getDeployKeys() throws IOException {
        List<GHDeployKey> list = new ArrayList<GHDeployKey>(
                Arrays.asList(root.retrieve().to(getApiTailUrl("keys"), GHDeployKey[].class)));
        for (GHDeployKey h : list)
            h.wrap(this);
        return list;
    }

    /**
     * Forked repositories have a 'source' attribute that specifies the ultimate source of the forking chain.
     *
     * @return {@link GHRepository} that points to the root repository where this repository is forked (indirectly or
     *         directly) from. Otherwise null.
     * @throws IOException
     *             the io exception
     * @see #getParent() #getParent()
     */
    public GHRepository getSource() throws IOException {
        if (source == null)
            return null;
        if (source.root == null)
            source = root.getRepository(source.getFullName());
        return source;
    }

    /**
     * Forked repositories have a 'parent' attribute that specifies the repository this repository is directly forked
     * from. If we keep traversing {@link #getParent()} until it returns null, that is {@link #getSource()}.
     *
     * @return {@link GHRepository} that points to the repository where this repository is forked directly from.
     *         Otherwise null.
     * @throws IOException
     *             the io exception
     * @see #getSource() #getSource()
     */
    public GHRepository getParent() throws IOException {
        if (parent == null)
            return null;
        if (parent.root == null)
            parent = root.getRepository(parent.getFullName());
        return parent;
    }

    /**
     * Subscribes to this repository to get notifications.
     *
     * @param subscribed
     *            the subscribed
     * @param ignored
     *            the ignored
     * @return the gh subscription
     * @throws IOException
     *             the io exception
     */
    public GHSubscription subscribe(boolean subscribed, boolean ignored) throws IOException {
        return new Requester(root).with("subscribed", subscribed).with("ignored", ignored).method("PUT")
                .to(getApiTailUrl("subscription"), GHSubscription.class).wrapUp(this);
    }

    /**
     * Returns the current subscription.
     *
     * @return null if no subscription exists.
     * @throws IOException
     *             the io exception
     */
    public GHSubscription getSubscription() throws IOException {
        try {
            return root.retrieve().to(getApiTailUrl("subscription"), GHSubscription.class).wrapUp(this);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * List contributors paged iterable.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<Contributor> listContributors() throws IOException {
        return root.retrieve().asPagedIterable(getApiTailUrl("contributors"), Contributor[].class,
                item -> item.wrapUp(root));
    }

    /**
     * The type Contributor.
     */
    public static class Contributor extends GHUser {
        private int contributions;

        /**
         * Gets contributions.
         *
         * @return the contributions
         */
        public int getContributions() {
            return contributions;
        }

        @Override
        public int hashCode() {
            // We ignore contributions in the calculation
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            // We ignore contributions in the calculation
            return super.equals(obj);
        }
    }

    /**
     * Returns the statistics for this repository.
     *
     * @return the statistics
     */
    public GHRepositoryStatistics getStatistics() {
        // TODO: Use static object and introduce refresh() method,
        // instead of returning new object each time.
        return new GHRepositoryStatistics(this);
    }

    /**
     * Create a project for this repository.
     *
     * @param name
     *            the name
     * @param body
     *            the body
     * @return the gh project
     * @throws IOException
     *             the io exception
     */
    public GHProject createProject(String name, String body) throws IOException {
        return root.retrieve().method("POST").withPreview(INERTIA).with("name", name).with("body", body)
                .to(getApiTailUrl("projects"), GHProject.class).wrap(this);
    }

    /**
     * Returns the projects for this repository.
     *
     * @param status
     *            The status filter (all, open or closed).
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHProject> listProjects(final GHProject.ProjectStateFilter status) throws IOException {
        return root.retrieve().withPreview(INERTIA).with("state", status).asPagedIterable(getApiTailUrl("projects"),
                GHProject[].class, item -> item.wrap(GHRepository.this));
    }

    /**
     * Returns open projects for this repository.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHProject> listProjects() throws IOException {
        return listProjects(GHProject.ProjectStateFilter.OPEN);
    }

    /**
     * Render a Markdown document.
     * <p>
     * In {@linkplain MarkdownMode#GFM GFM mode}, issue numbers and user mentions are linked accordingly.
     *
     * @param text
     *            the text
     * @param mode
     *            the mode
     * @return the reader
     * @throws IOException
     *             the io exception
     * @see GitHub#renderMarkdown(String) GitHub#renderMarkdown(String)
     */
    public Reader renderMarkdown(String text, MarkdownMode mode) throws IOException {
        return new InputStreamReader(
                new Requester(root).with("text", text).with("mode", mode == null ? null : mode.toString())
                        .with("context", getFullName()).asStream("/markdown"),
                "UTF-8");
    }

    /**
     * List all the notifications in a repository for the current user.
     *
     * @return the gh notification stream
     */
    public GHNotificationStream listNotifications() {
        return new GHNotificationStream(root, getApiTailUrl("/notifications"));
    }

    /**
     * <a href=
     * "https://developer.github.com/v3/repos/traffic/#views">https://developer.github.com/v3/repos/traffic/#views</a>
     *
     * @return the view traffic
     * @throws IOException
     *             the io exception
     */
    public GHRepositoryViewTraffic getViewTraffic() throws IOException {
        return root.retrieve().to(getApiTailUrl("/traffic/views"), GHRepositoryViewTraffic.class);
    }

    /**
     * <a href=
     * "https://developer.github.com/v3/repos/traffic/#clones">https://developer.github.com/v3/repos/traffic/#clones</a>
     *
     * @return the clone traffic
     * @throws IOException
     *             the io exception
     */
    public GHRepositoryCloneTraffic getCloneTraffic() throws IOException {
        return root.retrieve().to(getApiTailUrl("/traffic/clones"), GHRepositoryCloneTraffic.class);
    }

    @Override
    public int hashCode() {
        return ("Repository:" + getOwnerName() + ":" + name).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof GHRepository) {
            GHRepository that = (GHRepository) obj;
            return this.getOwnerName().equals(that.getOwnerName()) && this.name.equals(that.name);
        }
        return false;
    }

    String getApiTailUrl(String tail) {
        if (tail.length() > 0 && !tail.startsWith("/"))
            tail = '/' + tail;
        return "/repos/" + getOwnerName() + "/" + name + tail;
    }

    /**
     * Get all issue events for this repository. See
     * https://developer.github.com/v3/issues/events/#list-events-for-a-repository
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHIssueEvent> listIssueEvents() throws IOException {
        return root.retrieve().asPagedIterable(getApiTailUrl("issues/events"), GHIssueEvent[].class,
                item -> item.wrapUp(root));
    }

    /**
     * Get a single issue event. See https://developer.github.com/v3/issues/events/#get-a-single-event
     *
     * @param id
     *            the id
     * @return the issue event
     * @throws IOException
     *             the io exception
     */
    public GHIssueEvent getIssueEvent(long id) throws IOException {
        return root.retrieve().to(getApiTailUrl("issues/events/" + id), GHIssueEvent.class).wrapUp(root);
    }

    // Only used within listTopics().
    private static class Topics {
        public List<String> names;
    }

    /**
     * Return the topics for this repository. See
     * https://developer.github.com/v3/repos/#list-all-topics-for-a-repository
     *
     * @return the list
     * @throws IOException
     *             the io exception
     */
    public List<String> listTopics() throws IOException {
        Topics topics = root.retrieve().withPreview(MERCY).to(getApiTailUrl("topics"), Topics.class);
        return topics.names;
    }

    /**
     * Set the topics for this repository. See
     * https://developer.github.com/v3/repos/#replace-all-topics-for-a-repository
     *
     * @param topics
     *            the topics
     * @throws IOException
     *             the io exception
     */
    public void setTopics(List<String> topics) throws IOException {
        Requester requester = new Requester(root);
        requester.with("names", topics);
        requester.method("PUT").withPreview(MERCY).to(getApiTailUrl("topics"));
    }
}
