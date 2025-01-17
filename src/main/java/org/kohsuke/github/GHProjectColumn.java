package org.kohsuke.github;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;

import static org.kohsuke.github.Previews.INERTIA;

/**
 * The type GHProjectColumn.
 *
 * @author Gunnar Skjold
 */
public class GHProjectColumn extends GHObject {
    protected GitHub root;
    protected GHProject project;

    private String name;
    private String project_url;

    @Override
    public URL getHtmlUrl() throws IOException {
        return null;
    }

    /**
     * Wrap gh project column.
     *
     * @param root
     *            the root
     * @return the gh project column
     */
    public GHProjectColumn wrap(GitHub root) {
        this.root = root;
        return this;
    }

    /**
     * Wrap gh project column.
     *
     * @param project
     *            the project
     * @return the gh project column
     */
    public GHProjectColumn wrap(GHProject project) {
        this.project = project;
        this.root = project.root;
        return this;
    }

    /**
     * Gets root.
     *
     * @return the root
     */
    public GitHub getRoot() {
        return root;
    }

    /**
     * Gets project.
     *
     * @return the project
     * @throws IOException
     *             the io exception
     */
    public GHProject getProject() throws IOException {
        if (project == null) {
            try {
                project = root.retrieve().to(getProjectUrl().getPath(), GHProject.class).wrap(root);
            } catch (FileNotFoundException e) {
                return null;
            }
        }
        return project;
    }

    /**
     * Gets name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets project url.
     *
     * @return the project url
     */
    public URL getProjectUrl() {
        return GitHub.parseURL(project_url);
    }

    /**
     * Sets name.
     *
     * @param name
     *            the name
     * @throws IOException
     *             the io exception
     */
    public void setName(String name) throws IOException {
        edit("name", name);
    }

    private void edit(String key, Object value) throws IOException {
        new Requester(root).withPreview(INERTIA).with(key, value).method("PATCH").to(getApiRoute());
    }

    /**
     * Gets api route.
     *
     * @return the api route
     */
    protected String getApiRoute() {
        return String.format("/projects/columns/%d", id);
    }

    /**
     * Delete.
     *
     * @throws IOException
     *             the io exception
     */
    public void delete() throws IOException {
        new Requester(root).withPreview(INERTIA).method("DELETE").to(getApiRoute());
    }

    /**
     * List cards paged iterable.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHProjectCard> listCards() throws IOException {
        final GHProjectColumn column = this;
        return root.retrieve().withPreview(INERTIA).asPagedIterable(String.format("/projects/columns/%d/cards", id),
                GHProjectCard[].class, item -> item.wrap(column));
    }

    /**
     * Create card gh project card.
     *
     * @param note
     *            the note
     * @return the gh project card
     * @throws IOException
     *             the io exception
     */
    public GHProjectCard createCard(String note) throws IOException {
        return root.retrieve().method("POST").withPreview(INERTIA).with("note", note)
                .to(String.format("/projects/columns/%d/cards", id), GHProjectCard.class).wrap(this);
    }

    /**
     * Create card gh project card.
     *
     * @param issue
     *            the issue
     * @return the gh project card
     * @throws IOException
     *             the io exception
     */
    public GHProjectCard createCard(GHIssue issue) throws IOException {
        return root.retrieve().method("POST").withPreview(INERTIA)
                .with("content_type", issue instanceof GHPullRequest ? "PullRequest" : "Issue")
                .with("content_id", issue.getId())
                .to(String.format("/projects/columns/%d/cards", id), GHProjectCard.class).wrap(this);
    }
}
